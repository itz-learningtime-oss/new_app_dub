"""Candidate renderers. Heavy imports stay inside functions so the rest of the
harness works on a machine with no ML stack installed.
"""

from __future__ import annotations

import inspect
import os
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import config, textnorm


@dataclass
class RenderOutcome:
    candidate: str
    sentence_id: str
    wav: str
    seconds: float
    wall_s: float
    sample_rate: int
    note: str = ""


class RenderSkip(RuntimeError):
    """Raised when a candidate cannot run here (missing deps, gated model...)."""


_MODEL_CACHE: dict[str, object] = {}


def _seed_everything() -> None:
    np.random.seed(config.SEED)
    try:
        import torch

        torch.manual_seed(config.SEED)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(config.SEED)
    except Exception:
        pass


def _filter_kwargs(callable_obj, kwargs: dict) -> dict:
    try:
        params = inspect.signature(callable_obj).parameters
    except (TypeError, ValueError):
        return kwargs
    if any(p.kind is p.VAR_KEYWORD for p in params.values()):
        return kwargs
    return {k: v for k, v in kwargs.items() if k in params}


def _save(path: Path, audio: np.ndarray, rate: int) -> None:
    from .audioio import save

    audio = np.asarray(audio, dtype=np.float32).reshape(-1)
    save(path, audio, rate)


def _seconds(audio: np.ndarray, rate: int) -> float:
    return float(np.asarray(audio).size) / float(rate) if rate else 0.0


def _chatterbox_params(variant: str) -> dict:
    params = dict(config.CHATTERBOX_DEFAULTS if "default" in variant else config.CONFIG_B)
    params["cfg_weight"] = config.CFG_WEIGHT
    return params


T3_FILES = {
    "v2": ("ResembleAI/chatterbox", "t3_mtl23ls_v2.safetensors"),
    "v3": ("ResembleAI/chatterbox", "t3_mtl23ls_v3.safetensors"),
}
HI_REPO = "ResembleAI/Chatterbox-Multilingual-hi"
HI_T3 = "t3_hi.safetensors"
S3GEN_V3 = ("ResembleAI/chatterbox", "s3gen_v3.pt")


def _load_chatterbox(candidate: config.Candidate):
    """Return (model, checkpoint_note). The note records what was really loaded.

    Weights are applied by hand instead of through ``from_pretrained(t3_model=)``
    or ``from_local()``: chatterbox 0.1.7 has neither, and the earlier sweep
    silently skipped both candidates because of it.
    """
    cache_key = f"chatterbox:{candidate.variant}:{candidate.t3_model or ''}"
    if cache_key in _MODEL_CACHE:
        return _MODEL_CACHE[cache_key]

    # One model at a time: the Hindi/v3 checkpoints are ~2 GB each and a 16 GB
    # T4 should not hold two of them plus a decoder.
    if _MODEL_CACHE:
        _MODEL_CACHE.clear()
        import gc

        gc.collect()
        try:
            import torch

            torch.cuda.empty_cache()
        except Exception:
            pass

    try:
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS
    except ImportError as error:  # pragma: no cover - environment dependent
        raise RenderSkip(f"chatterbox-tts is not installed ({error})") from error

    device = config.device()
    model = ChatterboxMultilingualTTS.from_pretrained(device=device)
    note = "t3=library default; s3gen=base"

    if candidate.variant.startswith("hi"):
        try:
            path = _hi_t3_path()
            mode = _apply_t3(model, path, "t3_hi")
        except RenderSkip:
            raise
        except Exception as error:  # network, gated, disk...
            raise RenderSkip(f"Hindi T3 could not be loaded: {error}") from error
        note = f"t3=t3_hi ({mode}); s3gen={_apply_s3gen_v3(model)}"
    elif candidate.t3_model:
        if candidate.t3_model not in T3_FILES:
            raise RenderSkip(f"unknown T3 checkpoint {candidate.t3_model!r}")
        try:
            path = _hub_file(*T3_FILES[candidate.t3_model])
            mode = _apply_t3(model, path, f"t3_{candidate.t3_model}")
        except RenderSkip:
            raise
        except Exception as error:
            raise RenderSkip(
                f"T3 {candidate.t3_model} could not be loaded: {error}"
            ) from error
        note = f"t3={candidate.t3_model} ({mode}); s3gen={_apply_s3gen_v3(model)}"

    _MODEL_CACHE[cache_key] = (model, note)
    return model, note


def _hub_file(repo_id: str, filename: str) -> Path:
    from huggingface_hub import hf_hub_download

    return Path(hf_hub_download(repo_id, filename, token=os.environ.get("HF_TOKEN")))


def _hi_t3_path() -> Path:
    """PHASE0_CB_HI_DIR may hold a pre-downloaded t3_hi.safetensors."""
    override = os.environ.get("PHASE0_CB_HI_DIR")
    if override:
        candidate = Path(override)
        if candidate.is_dir():
            candidate = candidate / HI_T3
        if not candidate.exists():
            raise RenderSkip(f"PHASE0_CB_HI_DIR has no {HI_T3}: {candidate}")
        return candidate
    return _hub_file(HI_REPO, HI_T3)


def _strip_prefix(state: dict, *prefixes: str) -> dict:
    stripped = {}
    for key, value in state.items():
        for prefix in prefixes:
            if key.startswith(prefix):
                key = key[len(prefix):]
                break
        stripped[key] = value
    return stripped


def _unwrap(state):
    inner = state.get("model") if isinstance(state, dict) else None
    if isinstance(inner, (list, tuple)) and inner:
        return inner[0]
    return state


def _load_into(module, state: dict, what: str) -> str:
    """strict first; on a shape mismatch report exactly which keys disagree."""
    try:
        module.load_state_dict(state, strict=True)
        return "strict"
    except Exception as strict_error:
        missing, unexpected = module.load_state_dict(state, strict=False)
        if missing or unexpected:
            raise RenderSkip(
                f"{what} does not fit this chatterbox build "
                f"({len(missing)} missing, {len(unexpected)} unexpected; "
                f"missing e.g. {sorted(missing)[:2]}, "
                f"unexpected e.g. {sorted(unexpected)[:2]})"
            ) from strict_error
        return "loose"


def _apply_t3(model, path: Path, label: str) -> str:
    from safetensors.torch import load_file

    t3 = getattr(model, "t3", None)
    if t3 is None:
        raise RenderSkip("this chatterbox build has no 't3' module")
    state = _strip_prefix(_unwrap(load_file(str(path))), "t3.", "model.")
    mode = _load_into(t3, state, label)
    del state
    t3.to(model.device).eval()
    return mode


def _apply_s3gen_v3(model) -> str:
    """V3 T3 checkpoints belong with the V3 decoder; keep the base one if unable."""
    if os.environ.get("PHASE0_NO_S3GEN_SWAP"):
        return "base (swap disabled)"
    s3gen = getattr(model, "s3gen", None)
    if s3gen is None:
        return "base (no s3gen module)"
    try:
        import torch

        state = torch.load(_hub_file(*S3GEN_V3), map_location="cpu", weights_only=True)
        state = _strip_prefix(_unwrap(state), "s3gen.", "model.")
        mode = _load_into(s3gen, state, "s3gen_v3")
        del state
        s3gen.to(model.device).eval()
        return f"v3 ({mode})"
    except RenderSkip as error:
        return f"base ({error})"
    except Exception as error:  # download / torch < 2.0 weights_only...
        return f"base ({type(error).__name__}: {error})"


def _render_chatterbox(candidate: config.Candidate, text: str, reference: Path,
                       out: Path) -> tuple[np.ndarray, int, str]:
    model, checkpoint = _load_chatterbox(candidate)
    params = _chatterbox_params(candidate.variant)
    _seed_everything()

    call_kwargs = _filter_kwargs(
        model.generate,
        {
            "language_id": config.TTS_LANGUAGE_ID,
            "audio_prompt_path": str(reference),
            **params,
        },
    )
    audio = model.generate(text, **call_kwargs)
    if hasattr(audio, "detach"):
        audio = audio.detach().cpu().float().numpy()
    audio = np.asarray(audio, dtype=np.float32).reshape(-1)
    rate = int(getattr(model, "sr", 24000))
    note = (f"{checkpoint}; "
            f"params={ {k: v for k, v in call_kwargs.items() if k != 'audio_prompt_path'} }")
    return audio, rate, note


def _render_indicf5(candidate: config.Candidate, text: str, reference: Path,
                    ref_text: str | None, out: Path) -> tuple[np.ndarray, int, str]:
    if not ref_text:
        raise RenderSkip("IndicF5 needs the exact reference transcript (--ref-text)")
    cache_key = "indicf5"
    model = _MODEL_CACHE.get(cache_key)
    if model is None:
        try:
            from transformers import AutoModel

            model = AutoModel.from_pretrained(
                "ai4bharat/IndicF5", trust_remote_code=True
            )
            if config.device() == "cuda":
                model = model.cuda()
        except Exception as error:  # gated repo, missing deps...
            raise RenderSkip(f"IndicF5 unavailable: {error}") from error
        _MODEL_CACHE[cache_key] = model

    _seed_everything()
    result = model(text, ref_audio_path=str(reference), ref_text=ref_text)
    rate = int(os.environ.get("PHASE0_INDICF5_SR", "24000"))
    note = "indicf5 teacher"
    if isinstance(result, tuple):
        audio, maybe_rate = result[0], result[1]
        if isinstance(maybe_rate, (int, float)) and maybe_rate > 1000:
            rate = int(maybe_rate)
        note += f" (returned tuple, sr={rate})"
    else:
        audio = result
    if hasattr(audio, "detach"):
        audio = audio.detach().cpu().float().numpy()
    return np.asarray(audio, dtype=np.float32).reshape(-1), rate, note


def _render_voxcpm2(candidate: config.Candidate, text: str, reference: Path,
                    ref_text: str | None, out: Path) -> tuple[np.ndarray, int, str]:
    cache_key = "voxcpm2"
    model = _MODEL_CACHE.get(cache_key)
    if model is None:
        try:
            from voxcpm import VoxCPM

            model = VoxCPM.from_pretrained("openbmb/VoxCPM2", load_denoiser=False)
        except Exception as error:
            raise RenderSkip(f"VoxCPM2 unavailable: {error}") from error
        _MODEL_CACHE[cache_key] = model

    _seed_everything()
    attempts = [
        {
            "text": text,
            "reference_wav_path": str(reference),
            "seed": config.SEED,
            **config.VOXCPM2_SETTINGS,
        },
        {
            "text": text,
            "prompt_wav_path": str(reference),
            "prompt_text": ref_text or "",
            "seed": config.SEED,
            **config.VOXCPM2_SETTINGS,
        },
        {"text": text, "seed": config.SEED, **config.VOXCPM2_SETTINGS},
    ]
    last_error: Exception | None = None
    for attempt in attempts:
        try:
            audio = model.generate(**_filter_kwargs(model.generate, attempt))
            break
        except TypeError as error:
            last_error = error
    else:  # noqa: PLW0120 - loop exhausted
        raise RenderSkip(f"VoxCPM2 generate() signature not supported: {last_error}")

    if hasattr(audio, "detach"):
        audio = audio.detach().cpu().float().numpy()
    rate = 48000
    tts = getattr(model, "tts_model", None)
    if tts is not None and getattr(tts, "sample_rate", None):
        rate = int(tts.sample_rate)
    note = "voxcpm2, reference mode requested" if len(attempts) > 0 else ""
    return np.asarray(audio, dtype=np.float32).reshape(-1), rate, note


def _gguf_paths(variant: str) -> tuple[str, str] | None:
    upper = variant.upper()
    backbone = os.environ.get(f"PHASE0_CB_{upper}_BACKBONE")
    codec = os.environ.get(f"PHASE0_CB_{upper}_CODEC")
    if backbone and codec:
        return backbone, codec
    return None


def _render_gguf(candidate: config.Candidate, text: str, reference: Path,
                 out: Path) -> tuple[np.ndarray, int, str]:
    cli = os.environ.get("PHASE0_TTS_CLI")
    if not cli:
        raise RenderSkip("Set PHASE0_TTS_CLI to a codec.cpp 'tts-cli' build")
    paths = _gguf_paths(candidate.variant)
    if not paths:
        raise RenderSkip(
            f"Set PHASE0_CB_{candidate.variant.upper()}_BACKBONE / _CODEC to the GGUF pair"
        )
    backbone, codec = paths
    command = [
        cli, "synthesize",
        "--backbone", backbone,
        "--model", codec,
        "--ref-audio", str(reference),
        "--text", f"[{config.TTS_LANGUAGE_ID}]{text}",
        "--output", str(out),
    ]
    extra = os.environ.get("PHASE0_TTS_EXTRA", "").strip()
    if extra:
        command.extend(shlex.split(extra))

    run = subprocess.run(command, capture_output=True, text=True, timeout=3600)
    if run.returncode != 0:
        raise RenderSkip(
            "tts-cli failed: " + (run.stderr.strip() or run.stdout.strip())[-600:]
        )
    from .audioio import load

    audio, rate = load(out)
    return audio, rate, "gguf via codec.cpp"


def render(candidate: config.Candidate, sentence: dict, reference: Path,
           ref_text: str | None, out: Path) -> RenderOutcome:
    out.parent.mkdir(parents=True, exist_ok=True)
    text = sentence["text"]
    if candidate.spoken_numbers:
        text = textnorm.to_spoken(text)

    started = time.perf_counter()
    if candidate.family == "chatterbox":
        audio, rate, note = _render_chatterbox(candidate, text, reference, out)
    elif candidate.family == "indicf5":
        audio, rate, note = _render_indicf5(candidate, text, reference, ref_text, out)
    elif candidate.family == "voxcpm2":
        audio, rate, note = _render_voxcpm2(candidate, text, reference, ref_text, out)
    elif candidate.family == "gguf":
        audio, rate, note = _render_gguf(candidate, text, reference, out)
    else:  # pragma: no cover - config guard
        raise RenderSkip(f"unknown family {candidate.family}")
    wall = time.perf_counter() - started

    _save(out, audio, rate)
    return RenderOutcome(
        candidate=candidate.key,
        sentence_id=sentence["id"],
        wav=str(out),
        seconds=_seconds(audio, rate),
        wall_s=wall,
        sample_rate=rate,
        note=note,
    )


def probe(candidate: config.Candidate) -> str:
    """Cheap availability check used by --dry-run and the CLI banner."""
    if candidate.family == "gguf":
        if not os.environ.get("PHASE0_TTS_CLI"):
            return "needs PHASE0_TTS_CLI"
        if not _gguf_paths(candidate.variant):
            return f"needs PHASE0_CB_{candidate.variant.upper()}_BACKBONE/_CODEC"
        return "ready"
    if candidate.family == "chatterbox":
        try:
            import chatterbox  # noqa: F401
        except Exception:
            return "needs: pip install chatterbox-tts"
        if candidate.variant.startswith("hi"):
            if os.environ.get("PHASE0_CB_HI_DIR"):
                return "ready (local t3_hi)"
            return "ready (downloads hi T3, ~2.1 GB)"
        return "ready"
    if candidate.family == "indicf5":
        try:
            import transformers  # noqa: F401
        except Exception:
            return "needs: pip install transformers"
        if not os.environ.get("HF_TOKEN"):
            return "ready (gated: set HF_TOKEN if the repo requires login)"
        return "ready"
    if candidate.family == "voxcpm2":
        try:
            import voxcpm  # noqa: F401
        except Exception:
            return "needs: pip install voxcpm"
        return "ready"
    return "unknown"

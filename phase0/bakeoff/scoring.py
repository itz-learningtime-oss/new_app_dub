"""Scoring: Hindi ASR (WER/CER), speaker similarity, UTMOS22, RTF.

Every heavy model is loaded lazily and cached, because a Colab session should
load Whisper once, not 84 times.
"""

from __future__ import annotations

import csv
import json
import math
import os
from pathlib import Path

import numpy as np

from . import audioio, config, textnorm

ASR_MODEL = os.environ.get("PHASE0_ASR_MODEL", "large-v3")
ASR_CACHE_DIR = os.environ.get("PHASE0_CACHE", str(Path.home() / ".cache" / "phase0"))
SPEAKER_MODEL = "speechbrain/spkrec-ecapa-voxceleb"
# tarepan/SpeechMOS ships the UTMOS22 weights and a working hubconf; the original
# sarulab-speech/UTMOS22 repo has no hubconf.py, which is why torch.hub failed.
UTMOS_HUBS = (
    ("tarepan/SpeechMOS:v1.2.0", "utmos22_strong"),
    ("sarulab-speech/UTMOS22", "strong"),
)
SCORE_CACHE = "_score_cache.json"
FIELDNAMES = [
    "candidate", "sentence_id", "seconds", "wall_s", "rtf",
    "wer", "cer", "numeral_recall", "speaker_sim", "utmos", "transcript",
    "word_edits", "word_count", "char_edits", "char_count",
]

_CACHE: dict[str, object] = {}
_UTMOS_ERROR: str | None = None


def _asr():
    if "asr" in _CACHE:
        return _CACHE["asr"]
    from faster_whisper import WhisperModel

    device = "cuda" if config.device() == "cuda" else "cpu"
    compute = "float16" if device == "cuda" else "int8"
    model = WhisperModel(ASR_MODEL, device=device, compute_type=compute,
                         download_root=ASR_CACHE_DIR)
    _CACHE["asr"] = model
    return model


def transcribe(path: str | Path) -> str:
    """Hindi ASR.

    The audio is decoded with soundfile and handed to faster-whisper as a numpy
    array, so the PyAV path is never used: faster-whisper 1.2.1 calls
    ``av.open(..., metadata_errors=...)``, which older PyAV builds reject with
    ``TypeError``. Our WAVs are already 16-bit PCM, so soundfile is enough.
    """
    audio, rate = audioio.load(path)
    audio = audioio.resample(audio, rate, 16000)
    if audio.size == 0:
        return ""
    segments, _info = _asr().transcribe(
        audio,
        language="hi",
        task="transcribe",
        beam_size=5,
        vad_filter=False,
        condition_on_previous_text=False,
        temperature=0.0,
    )
    return " ".join(segment.text.strip() for segment in segments).strip()


def _speaker():
    if "speaker" in _CACHE:
        return _CACHE["speaker"]
    from speechbrain.inference.speaker import EncoderClassifier

    # speechbrain wants "cuda:0"; plain "cuda" makes it warn and guess.
    device = "cuda:0" if config.device() == "cuda" else "cpu"
    classifier = EncoderClassifier.from_hparams(
        source=SPEAKER_MODEL,
        savedir=str(Path(ASR_CACHE_DIR) / "ecapa"),
        run_opts={"device": device},
    )
    _CACHE["speaker"] = classifier
    return classifier


def _embedding(path: str | Path) -> np.ndarray:
    """ECAPA embedding from our own decoder.

    speechbrain 1.1 removed the ``sample_rate`` argument from
    ``read_audio()``, so decode with soundfile and resample ourselves.
    """
    import torch

    classifier = _speaker()
    audio, rate = audioio.load(path)
    audio = audioio.resample(audio, rate, 16000)
    tensor = torch.from_numpy(audio.astype(np.float32)).unsqueeze(0)
    with torch.no_grad():
        embedding = classifier.encode_batch(tensor)
    return embedding.squeeze().cpu().numpy().astype(np.float64)


def speaker_similarity(reference: str | Path, generated: str | Path) -> float:
    a = _embedding(reference)
    b = _embedding(generated)
    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
    if denom == 0.0:
        return float("nan")
    return float(np.dot(a, b) / denom)


def _utmos():
    if "utmos" in _CACHE:
        return _CACHE["utmos"]
    import torch

    predictor = None
    errors: list[str] = []
    for repo, entry in UTMOS_HUBS:
        try:
            predictor = torch.hub.load(repo, entry, trust_repo=True)
            break
        except Exception as error:  # network, hubconf, version drift...
            errors.append(f"{repo}: {error}")
    if predictor is not None:
        predictor.eval()
    else:
        global _UTMOS_ERROR
        _UTMOS_ERROR = " | ".join(errors)
        print("  ! UTMOS unavailable (" + _UTMOS_ERROR + ")")
    _CACHE["utmos"] = predictor  # None is cached too: never retry per file
    return predictor


def utmos(path: str | Path) -> float:
    try:
        import torch

        predictor = _utmos()
        if predictor is None:
            return float("nan")
        audio, rate = audioio.load(path)
        audio = audioio.resample(audio, rate, 16000)
        tensor = torch.from_numpy(audio.astype(np.float32)).unsqueeze(0)
        with torch.no_grad():
            score = predictor(tensor)
        return float(np.asarray(score).reshape(-1)[0])
    except Exception as error:  # UTMOS is a nice-to-have, never fatal
        print(f"  ! UTMOS failed on {Path(path).name} ({error})")
        return float("nan")


def _row(candidate: str, sentence: dict, seconds: float, wall_s: float | None,
         hypothesis: str, similarity: float, naturalness: float) -> dict:
    errors = textnorm.wer(sentence["text"], hypothesis)
    numeral = textnorm.numeral_recall(sentence["text"], hypothesis)
    rtf = (float(wall_s) / seconds) if (wall_s is not None and seconds > 0) else float("nan")
    return {
        "candidate": candidate,
        "sentence_id": sentence["id"],
        "seconds": round(seconds, 3),
        "wall_s": round(float(wall_s), 3) if wall_s is not None else "",
        "rtf": round(rtf, 4) if not math.isnan(rtf) else "",
        "wer": round(errors["wer"], 4),
        "cer": round(errors["cer"], 4),
        "numeral_recall": "" if numeral is None else round(numeral, 4),
        "speaker_sim": round(similarity, 4) if not math.isnan(similarity) else "",
        "utmos": round(naturalness, 4) if not math.isnan(naturalness) else "",
        "transcript": hypothesis,
        "word_edits": int(errors["word_edits"]),
        "word_count": int(errors["word_count"]),
        "char_edits": int(errors["char_edits"]),
        "char_count": int(errors["char_count"]),
    }


def _timings(path: Path) -> dict[tuple[str, str], float]:
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as handle:
        return {(row["candidate"], row["sentence_id"]): float(row["wall_s"])
                for row in csv.DictReader(handle) if row.get("wall_s")}


def _cache_meta() -> dict:
    return {"asr": ASR_MODEL, "spk": SPEAKER_MODEL, "utmos": UTMOS_HUBS[0][0]}


def _read_cache(out_dir: Path) -> dict:
    """Per-WAV cache of ASR / SIM / UTMOS, so re-scoring is seconds not minutes."""
    path = out_dir / SCORE_CACHE
    data: dict = {}
    if path.exists():
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
    meta = data.get("meta") or {}
    current = _cache_meta()
    for section in ("asr", "spk", "utmos"):
        data.setdefault(section, {})
        if meta.get(section) != current[section]:
            data[section] = {}
    data["meta"] = current
    return data


def _write_cache(out_dir: Path, data: dict) -> None:
    (out_dir / SCORE_CACHE).write_text(
        json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8"
    )


def score_run(out_dir: Path) -> Path:
    from . import cli_io

    manifest = cli_io.read_json(out_dir / "manifest.json")
    sentences = {s["id"]: s for s in cli_io.read_json(config.SENTENCES_FILE)["sentences"]}
    reference = out_dir / "reference.wav"
    if not reference.exists():
        raise SystemExit(f"missing {reference}; run the render step first")

    timings = _timings(out_dir / "timings.csv")
    keys = manifest.get("candidates") or [c.key for c in config.CANDIDATES]
    rows: list[dict] = []

    cache = _read_cache(out_dir)
    reference_sha = audioio.sha256(reference)
    if cache.get("reference_sha") != reference_sha:
        cache["spk"] = {}
        cache["reference_sha"] = reference_sha

    for key in keys:
        folder = out_dir / key
        if not folder.is_dir():
            print(f"skip {key}: no renders")
            continue
        for wav in sorted(folder.glob("*.wav")):
            sentence = sentences.get(wav.stem)
            if sentence is None:
                print(f"skip {wav.name}: not in the sentence set")
                continue

            digest = audioio.sha256(wav)
            marks = []
            if digest in cache["asr"]:
                hypothesis = cache["asr"][digest]
                marks.append("asr=cached")
            else:
                print(f"score {key}/{wav.name} (asr) ...", end="", flush=True)
                hypothesis = transcribe(wav)
                cache["asr"][digest] = hypothesis
                print(" done", flush=True)

            if digest in cache["spk"]:
                similarity = cache["spk"][digest]
                similarity = float("nan") if similarity is None else float(similarity)
                marks.append("sim=cached")
            else:
                similarity = speaker_similarity(reference, wav)
                cache["spk"][digest] = None if math.isnan(similarity) else similarity

            if digest in cache["utmos"]:
                naturalness = cache["utmos"][digest]
                naturalness = float("nan") if naturalness is None else float(naturalness)
                marks.append("utmos=cached")
            else:
                naturalness = utmos(wav)
                cache["utmos"][digest] = (
                    None if math.isnan(naturalness) else naturalness
                )

            audio, rate = audioio.load(wav)
            seconds = float(audio.size) / float(rate) if rate else 0.0
            rows.append(_row(key, sentence, seconds, timings.get((key, wav.stem)),
                             hypothesis, similarity, naturalness))
            print(f"score {key}/{wav.name} "
                  f"wer={rows[-1]['wer']:.3f} cer={rows[-1]['cer']:.3f}"
                  + (f" [{', '.join(marks)}]" if marks else ""))
            _write_cache(out_dir, cache)

    if _UTMOS_ERROR:
        cache["utmos_error"] = _UTMOS_ERROR
    _write_cache(out_dir, cache)

    target = out_dir / "results.csv"
    with target.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDNAMES)
        writer.writeheader()
        writer.writerows(rows)
    print(f"wrote {target} ({len(rows)} rows)")
    return target

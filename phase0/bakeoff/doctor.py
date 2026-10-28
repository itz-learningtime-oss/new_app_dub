"""`run.py doctor` — one screen that says whether the Colab/Kaggle stack is sane.

Catches the two failure modes we actually hit: a torch trio that no longer
agrees (pip downgrades torch for a pinned candidate) and a missing candidate
package. Never imports a model, so it costs a couple of seconds.
"""

from __future__ import annotations

import importlib
import platform
import sys

from . import config

CORE = ["numpy", "scipy", "soundfile", "torch", "torchaudio", "torchvision",
        "faster_whisper", "speechbrain", "chatterbox", "transformers"]
OPTIONAL = ["vocos", "voxcpm", "safetensors", "numba"]
CANARY = "5.2.0"

# chatterbox 0.1.7 declares numpy<2 for python<3.13 and numpy>=2 for 3.13+.
# numba (pulled in by librosa) is the real ceiling: it refuses numpy > 2.2.
NUMPY_MAX = "2.3"


def _module(name: str) -> tuple[bool, str]:
    try:
        module = importlib.import_module(name)
    except Exception as error:  # noqa: BLE001 - report anything, never crash
        detail = str(error).strip().splitlines()[0][:90] if str(error).strip() else type(error).__name__
        return False, detail
    version = getattr(module, "__version__", "")
    return True, str(version)


def _xy(version: str) -> tuple[int, int] | None:
    try:
        parts = version.split("+")[0].split(".")
        return int(parts[0]), int(parts[1])
    except (IndexError, ValueError):
        return None


def main() -> int:
    print(f"python   {platform.python_version()}  ({sys.executable})")
    print(f"platform {platform.platform()}")
    print()

    failures: list[str] = []
    warnings: list[str] = []
    versions: dict[str, str] = {}

    for name in CORE + OPTIONAL:
        ok, detail = _module(name)
        marker = "ok  " if ok else "MISS"
        print(f"  {marker} {name:<14} {detail}")
        if ok:
            versions[name] = detail
        elif name in CORE:
            failures.append(name)

    if not failures:
        import torch

        print()
        print(f"torch    {torch.__version__}  cuda={torch.cuda.is_available()}  "
              f"devices={torch.cuda.device_count()}")
        if torch.cuda.is_available():
            name = torch.cuda.get_device_name(0)
            total = torch.cuda.get_device_properties(0).total_memory / 1024 ** 3
            print(f"gpu      {name}  {total:.1f} GB")

        torch_xy = _xy(versions.get("torch", ""))
        audio_xy = _xy(versions.get("torchaudio", ""))
        if torch_xy and audio_xy and torch_xy != audio_xy:
            warnings.append(
                f"torchaudio {versions['torchaudio']} must match torch "
                f"{versions['torch']}; reinstall the pair together"
            )

        vision_xy = _xy(versions.get("torchvision", ""))
        if torch_xy and vision_xy:
            # torch X.Y pairs with torchvision 0.(Y+15): 2.6 -> 0.21, 2.11 -> 0.26.
            expected = (0, torch_xy[1] + 15)
            if vision_xy != expected:
                warnings.append(
                    f"torchvision {versions['torchvision']} does not pair with torch "
                    f"{versions['torch']} (expected 0.{torch_xy[1] + 15}.x)"
                )

        if versions.get("transformers") and not versions["transformers"].startswith("5."):
            warnings.append(
                f"transformers {versions['transformers']} is not the chatterbox pin "
                f"({CANARY}); chatterbox may fail to import"
            )

        try:
            import numpy

            major = int(numpy.__version__.split(".")[0])
            if sys.version_info < (3, 13) and major >= 2:
                warnings.append(
                    f"numpy {numpy.__version__} is >=2 but chatterbox needs <2 on "
                    f"python {platform.python_version()}"
                )
            if sys.version_info >= (3, 13):
                minor = int(numpy.__version__.split(".")[1])
                if major < 2:
                    warnings.append(
                        f"numpy {numpy.__version__} is <2 but chatterbox needs >=2 "
                        f"on python {platform.python_version()}"
                    )
                elif (major, minor) >= (2, 3):
                    warnings.append(
                        f"numpy {numpy.__version__} is too new for numba "
                        f"(needs <{NUMPY_MAX}); pin numpy==2.2.6"
                    )
        except Exception:
            pass

    print()
    print(f"device   {config.device()}")
    print(f"seed     {config.SEED}")
    print(f"sentences {config.SENTENCES_FILE}")

    for warning in warnings:
        print(f"WARN     {warning}")
    if failures:
        print(f"FAIL     missing: {', '.join(failures)}")
        print("         pip install -r phase0/requirements.txt")
        return 1

    if warnings:
        print("WARN     stack usable, but fix the warnings before trusting timings")
    else:
        print("OK       stack looks consistent")
    return 0

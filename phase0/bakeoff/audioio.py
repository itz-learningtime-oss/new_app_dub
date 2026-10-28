"""WAV I/O, reference-audio inspection and resampling for the bake-off."""

from __future__ import annotations

import hashlib
import math
import os
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np


def _abspath(path: str | Path) -> str:
    """Absolute path without Path.resolve(): cloud-drive drivers on Windows
    reject GetFinalPathNameByHandle (WinError 1005)."""
    try:
        return str(Path(path).resolve())
    except OSError:
        return os.path.abspath(str(path))


@dataclass
class ReferenceReport:
    path: str
    sha256: str
    seconds: float
    sample_rate: int
    channels: int
    peak_dbfs: float
    rms_dbfs: float
    silence_fraction: float
    warnings: list[str]

    @property
    def usable(self) -> bool:
        blocking = [w for w in self.warnings if w.startswith("BLOCK")]
        return not blocking


def _dbfs(value: float) -> float:
    return 20.0 * math.log10(max(value, 1e-9))


def load(path: str | Path) -> tuple[np.ndarray, int]:
    """Read a WAV as float32 mono in [-1, 1]."""
    import soundfile as sf

    data, rate = sf.read(str(path), dtype="float32", always_2d=True)
    mono = data.mean(axis=1) if data.shape[1] > 1 else data[:, 0]
    return np.ascontiguousarray(mono, dtype=np.float32), int(rate)


def save(path: str | Path, audio: np.ndarray, rate: int) -> None:
    import soundfile as sf

    Path(path).parent.mkdir(parents=True, exist_ok=True)
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak > 0.999:
        audio = audio / peak * 0.999
    sf.write(str(path), np.asarray(audio, dtype=np.float32), int(rate), subtype="PCM_16")


def resample(audio: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate or audio.size == 0:
        return audio.astype(np.float32, copy=False)
    from math import gcd

    from scipy.signal import resample_poly

    divisor = gcd(int(source_rate), int(target_rate))
    up = int(target_rate // divisor)
    down = int(source_rate // divisor)
    return np.asarray(resample_poly(audio, up, down), dtype=np.float32)


def peak_normalize(audio: np.ndarray, target_dbfs: float = -1.0) -> np.ndarray:
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak <= 1e-6:
        return audio
    target = 10.0 ** (target_dbfs / 20.0)
    return (audio * (target / peak)).astype(np.float32)


def silence_fraction(audio: np.ndarray, rate: int, frame_ms: float = 50.0,
                     threshold_dbfs: float = -45.0) -> float:
    frame = max(1, int(rate * frame_ms / 1000.0))
    if audio.size < frame:
        return 1.0
    usable = audio[: audio.size - (audio.size % frame)].reshape(-1, frame)
    rms = np.sqrt(np.mean(np.square(usable, dtype=np.float64), axis=1))
    quiet = rms < 10.0 ** (threshold_dbfs / 20.0)
    return float(np.mean(quiet))


def inspect_reference(path: str | Path) -> ReferenceReport:
    import soundfile as sf

    path = Path(path)
    audio, rate = load(path)
    info = sf.info(str(path))
    seconds = float(audio.size) / float(rate) if rate else 0.0
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    rms = float(np.sqrt(np.mean(np.square(audio, dtype=np.float64)))) if audio.size else 0.0
    silence = silence_fraction(audio, rate)

    warnings: list[str] = []
    if seconds < 5.0:
        warnings.append(f"BLOCK too short ({seconds:.1f}s); every candidate needs at least 5s")
    if seconds > 20.0:
        warnings.append(f"WARN {seconds:.1f}s is long; 8-12s clones better and renders faster")
    if not (5.0 <= seconds <= 15.0):
        warnings.append("WARN outside the 5-15s window used by the plan")
    if info.channels > 1:
        warnings.append("WARN multi-channel input is downmixed; use a mono recording")
    if rate < 16000:
        warnings.append(f"WARN {rate} Hz is low; 24-48 kHz original is better")
    if peak < 0.02:
        warnings.append("BLOCK almost silent recording")
    if peak > 0.999:
        warnings.append("WARN clips at full scale; re-record slightly quieter")
    if silence > 0.45:
        warnings.append(f"WARN {silence:.0%} silence; trim before running the bake-off")
    if rms < 0.01:
        warnings.append("WARN quiet recording; raise the input gain")

    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return ReferenceReport(
        path=_abspath(path),
        sha256=digest,
        seconds=seconds,
        sample_rate=rate,
        channels=int(info.channels),
        peak_dbfs=_dbfs(peak),
        rms_dbfs=_dbfs(rms),
        silence_fraction=silence,
        warnings=warnings,
    )


def sha256(path: str | Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prepare_reference(path: str | Path, destination: str | Path) -> dict:
    """Copy the reference into the run directory, peak-normalised, unchanged rate.

    Peak normalisation rewrites the samples, so the copy's hash differs from the
    source's. Both are recorded: the source hash identifies the take across runs,
    the copy hash identifies the exact file every render actually used.
    """
    source_sha = sha256(path)
    audio, rate = load(path)
    normalised = peak_normalize(audio)
    save(destination, normalised, rate)
    report = asdict(inspect_reference(destination))
    report["source_sha256"] = source_sha
    report["source_path"] = _abspath(path)
    return report

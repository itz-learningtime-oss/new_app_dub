from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bakeoff import audioio  # noqa: E402


def tone(seconds: float, rate: int = 24000, dbfs: float = -3.0) -> np.ndarray:
    t = np.arange(int(seconds * rate), dtype=np.float32) / rate
    signal = np.sin(2 * np.pi * 220.0 * t).astype(np.float32)
    return signal * (10 ** (dbfs / 20.0))


class ReferenceTest(unittest.TestCase):
    def test_good_ten_second_clip_is_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "ref.wav"
            audioio.save(path, tone(10.0), 24000)
            report = audioio.inspect_reference(path)
            self.assertTrue(report.usable)
            self.assertAlmostEqual(report.seconds, 10.0, places=2)
            self.assertEqual(report.sample_rate, 24000)
            self.assertEqual(report.channels, 1)
            self.assertLess(report.silence_fraction, 0.05)
            self.assertEqual(len(report.sha256), 64)

    def test_short_clip_is_blocked(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "short.wav"
            audioio.save(path, tone(2.0), 24000)
            report = audioio.inspect_reference(path)
            self.assertFalse(report.usable)
            self.assertTrue(any(w.startswith("BLOCK") for w in report.warnings))

    def test_stereo_is_flagged_but_usable(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "stereo.wav"
            mono = tone(9.0)
            import soundfile as sf

            sf.write(str(path), np.stack([mono, mono], axis=1), 24000, subtype="PCM_16")
            report = audioio.inspect_reference(path)
            self.assertTrue(report.usable)
            self.assertEqual(report.channels, 2)
            self.assertTrue(any("multi-channel" in w for w in report.warnings))

    def test_silence_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "quiet.wav"
            silence = np.zeros(24000 * 8, dtype=np.float32)
            audioio.save(path, silence, 24000)
            report = audioio.inspect_reference(path)
            self.assertFalse(report.usable)
            self.assertGreater(report.silence_fraction, 0.9)


class AudioOpsTest(unittest.TestCase):
    def test_resample_keeps_duration(self):
        audio = tone(1.0, rate=24000)
        converted = audioio.resample(audio, 24000, 16000)
        self.assertAlmostEqual(converted.size, 16000, delta=2)
        self.assertEqual(converted.dtype, np.float32)

    def test_resample_noop_on_same_rate(self):
        audio = tone(0.5, rate=16000)
        converted = audioio.resample(audio, 16000, 16000)
        self.assertIs(converted, audio)

    def test_peak_normalize(self):
        quiet = tone(0.5) * 0.01
        loud = audioio.peak_normalize(quiet)
        self.assertAlmostEqual(float(np.max(np.abs(loud))), 10 ** (-1.0 / 20.0), places=3)

    def test_prepare_reference_writes_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "source.wav"
            target = Path(tmp) / "run" / "reference.wav"
            audioio.save(source, tone(9.0) * 0.2, 24000)
            info = audioio.prepare_reference(source, target)
            self.assertTrue(target.exists())
            self.assertEqual(info["sample_rate"], 24000)
            self.assertGreater(info["peak_dbfs"], -2.0)
            self.assertEqual(info["source_sha256"], audioio.sha256(source))
            self.assertNotEqual(info["source_sha256"], info["sha256"])


if __name__ == "__main__":
    unittest.main()

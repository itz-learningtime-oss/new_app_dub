from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bakeoff import audioio, cli, renderers  # noqa: E402


def _fake_render(candidate, sentence, reference, ref_text, out):
    audioio.save(out, np.zeros(2400, dtype=np.float32), 24000)
    return renderers.RenderOutcome(
        candidate=candidate.key,
        sentence_id=sentence["id"],
        wav=str(out),
        seconds=1.0,
        wall_s=0.5,
        sample_rate=24000,
        note="fake",
    )


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self._render = renderers.render
        self._probe = renderers.probe
        renderers.render = _fake_render
        renderers.probe = lambda candidate: "ready"

    def tearDown(self):
        renderers.render = self._render
        renderers.probe = self._probe

    def _reference(self, folder: Path) -> Path:
        rate = 24000
        t = np.arange(int(rate * 6.0), dtype=np.float32) / rate
        audioio.save(folder / "ref.wav", 0.3 * np.sin(2 * np.pi * 220 * t), rate)
        return folder / "ref.wav"

    def test_second_sweep_extends_candidate_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            reference = self._reference(folder)
            out = folder / "out"
            common = ["render", "--reference", str(reference), "--out", str(out),
                      "--limit", "1"]

            cli.main(common + ["--candidates", "cb_mtl_configB",
                               "--ref-text", "नमस्ते दुनिया"])
            cli.main(common + ["--candidates", "cb_mtl_configB_spoken"])

            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["candidates"],
                             ["cb_mtl_configB", "cb_mtl_configB_spoken"])
            self.assertEqual(manifest["reference_transcript"], "नमस्ते दुनिया")

    def test_unknown_candidate_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            reference = self._reference(folder)
            with self.assertRaises(SystemExit):
                cli.main(["render", "--reference", str(reference),
                          "--out", str(folder / "out"),
                          "--candidates", "cb_not_a_candidate", "--limit", "1"])


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import csv
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bakeoff import audioio, report  # noqa: E402


def rows():
    return [
        {"candidate": "cb_mtl_configB", "sentence_id": "n01", "seconds": "10",
         "wall_s": "60", "rtf": "6", "wer": "0.05", "cer": "0.02",
         "numeral_recall": "1.0", "speaker_sim": "0.84", "utmos": "3.9",
         "transcript": "ठीक", "word_edits": "1", "word_count": "20",
         "char_edits": "2", "char_count": "100"},
        {"candidate": "cb_mtl_configB", "sentence_id": "n02", "seconds": "10",
         "wall_s": "60", "rtf": "6", "wer": "0.09", "cer": "0.03",
         "numeral_recall": "1.0", "speaker_sim": "0.80", "utmos": "3.7",
         "transcript": "लगभग ठीक", "word_edits": "2", "word_count": "20",
         "char_edits": "3", "char_count": "100"},
        {"candidate": "indicf5", "sentence_id": "n01", "seconds": "10",
         "wall_s": "4", "rtf": "0.4", "wer": "0.20", "cer": "0.08",
         "numeral_recall": "", "speaker_sim": "0.70", "utmos": "4.2",
         "transcript": "गलत", "word_edits": "6", "word_count": "20",
         "char_edits": "9", "char_count": "100"},
    ]


class AggregateTest(unittest.TestCase):
    def test_means_and_order(self):
        summary = report.aggregate(rows())
        self.assertEqual([entry["key"] for entry in summary],
                         ["cb_mtl_configB", "indicf5"])
        first = summary[0]
        self.assertAlmostEqual(first["wer"], 0.07, places=6)
        self.assertAlmostEqual(first["speaker_sim"], 0.82, places=6)
        self.assertAlmostEqual(first["numeral_recall"], 1.0, places=6)
        self.assertAlmostEqual(first["wer_corpus"], 0.075, places=6)
        self.assertAlmostEqual(first["cer_corpus"], 0.025, places=6)
        self.assertAlmostEqual(first["wer_median"], 0.07, places=6)
        self.assertEqual(first["n"], 2)
        self.assertTrue(first["on_device"])
        self.assertFalse(summary[1]["on_device"])

    def test_missing_metric_is_skipped_not_zero(self):
        summary = report.aggregate(rows())
        self.assertIsNone(summary[1]["numeral_recall"])

    def test_verdict(self):
        summary = report.aggregate(rows())
        self.assertEqual(report._verdict(summary[0]), "PASS")
        self.assertEqual(report._verdict(summary[1]), "below bar")

    def test_missing_utmos_does_not_fail_a_good_candidate(self):
        good_but_no_utmos = {"wer": 0.04, "cer": 0.02, "speaker_sim": 0.81,
                             "numeral_recall": 1.0, "utmos": None}
        self.assertEqual(report._verdict(good_but_no_utmos), "PASS")

    def test_cer_bar_is_enforced(self):
        # near-miss words: WER ok, characters too mangled
        entry = {"wer": 0.10, "cer": 0.09, "speaker_sim": 0.81,
                 "numeral_recall": None, "utmos": None}
        self.assertEqual(report._verdict(entry), "below bar")

    def test_a_missed_numeral_fails_the_candidate(self):
        entry = {"wer": 0.05, "cer": 0.02, "speaker_sim": 0.81,
                 "numeral_recall": 0.67, "utmos": None}
        self.assertEqual(report._verdict(entry), "below bar")

    def test_runaway_is_flagged(self):
        entry = {"wer": 0.05, "cer": 0.02, "speaker_sim": 0.81,
                 "numeral_recall": 1.0, "utmos": None, "wer_max": 3.77}
        self.assertIn("runaway", report._verdict(entry))

    def test_missing_wer_or_sim_is_no_data(self):
        self.assertEqual(report._verdict({"wer": None, "speaker_sim": 0.9, "utmos": 4.0}),
                         "no data")
        self.assertEqual(report._verdict({"wer": 0.1, "speaker_sim": None, "utmos": 4.0}),
                         "no data")

    def test_table_renders_percentages(self):
        table = report.markdown_table(report.aggregate(rows()))
        self.assertIn("| 1 | Chatterbox Multilingual + Config B | yes | 7.0% |", table)
        self.assertIn("CER corpus", table)
        self.assertIn("PASS", table)
        self.assertIn("—", table)  # blank metric renders as an em dash

    def test_report_contains_per_sentence_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            with (out / "results.csv").open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(rows()[0]))
                writer.writeheader()
                writer.writerows(rows())
            text = report.build_report(out)
            self.assertIn("### Per-sentence detail", text)
            self.assertIn("cb_mtl_configB", text)
            self.assertIn("### Sentences over the WER bar", text)
            self.assertIn("`indicf5` / `n01`", text)

    def test_publish_requires_markers(self):
        with tempfile.TemporaryDirectory() as tmp:
            doc = Path(tmp) / "doc.md"
            doc.write_text("# no markers here\n", encoding="utf-8")
            out = Path(tmp)
            with (out / "results.csv").open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(handle, fieldnames=list(rows()[0]))
                writer.writeheader()
                writer.writerows(rows())
            previous = report.DOC_PATH
            report.DOC_PATH = doc
            try:
                with self.assertRaises(SystemExit):
                    report.write_report(out, publish=True)
            finally:
                report.DOC_PATH = previous


class BlindTest(unittest.TestCase):
    def test_blind_sheet_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            import numpy as np

            for key in ("cand_a", "cand_b"):
                for sentence in ("n01", "n02"):
                    audioio.save(out / key / f"{sentence}.wav",
                                 np.zeros(2400, dtype=np.float32), 24000)
            target = report.blind_ab(out, "cand_a", "cand_b")
            page = target.read_text(encoding="utf-8")
            self.assertEqual(page.count('class="card"'), 2)
            self.assertEqual(page.count("<audio controls"), 4)
            self.assertIn('charset="utf-8"', page)
            self.assertIn("आज सुबह", page)

            with (out / "blind_ratings.csv").open(encoding="utf-8") as handle:
                mapping = list(csv.DictReader(handle))
            self.assertEqual(len(mapping), 2)
            self.assertTrue({row["slot_a_key"] for row in mapping} <= {"cand_a", "cand_b"})
            self.assertTrue({row["slot_b_key"] for row in mapping} <= {"cand_a", "cand_b"})


if __name__ == "__main__":
    unittest.main()

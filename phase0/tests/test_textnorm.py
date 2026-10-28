from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from bakeoff import textnorm  # noqa: E402


class NumeralsTest(unittest.TestCase):
    def test_digit_grouping_indian_style(self):
        self.assertEqual(textnorm.extract_numerals("24,07,000 रुपये"), [2407000])

    def test_word_multiplicative(self):
        self.assertEqual(textnorm.extract_numerals("पंद्रह सौ लोग"), [1500])

    def test_word_lakh_and_hazar(self):
        self.assertEqual(
            textnorm.extract_numerals("चौबीस लाख सात हज़ार रुपये"), [2407000]
        )

    def test_crore(self):
        self.assertEqual(textnorm.extract_numerals("दो करोड़ बीस लाख"), [22000000])

    def test_multiple_numbers_in_order(self):
        self.assertEqual(textnorm.extract_numerals("दस बजकर तीस मिनट"), [10, 30])

    def test_no_numbers(self):
        self.assertEqual(textnorm.extract_numerals("आज मौसम अच्छा है"), [])

    def test_devanagari_digits(self):
        self.assertEqual(textnorm.extract_numerals("२०२६ में"), [2026])


class CanonicalTest(unittest.TestCase):
    def test_digits_and_words_become_one_token(self):
        digits = textnorm.canonicalize_numbers("कंपनी ने 24,07,000 रुपये कमाए")
        words = textnorm.canonicalize_numbers("कंपनी ने चौबीस लाख सात हज़ार रुपये कमाए")
        self.assertEqual(digits, words)
        self.assertIn("<num:2407000>", digits)

    def test_wer_treats_same_number_as_equal(self):
        result = textnorm.wer(
            "कंपनी ने 24,07,000 रुपये कमाए।",
            "कंपनी ने चौबीस लाख सात हज़ार रुपये कमाए",
        )
        self.assertEqual(result["wer"], 0.0)


class WerTest(unittest.TestCase):
    def test_identical_after_punctuation_and_danda(self):
        result = textnorm.wer(
            "पहले हम बाज़ार जाएँगे। फिर घर लौटेंगे।",
            "पहले हम बाज़ार जाएँगे फिर घर लौटेंगे",
        )
        self.assertEqual(result["wer"], 0.0)

    def test_zwnj_removed(self):
        self.assertEqual(
            textnorm.normalize_for_wer("क्\u200dष"),
            textnorm.normalize_for_wer("क्ष"),
        )

    def test_one_substitution(self):
        result = textnorm.wer("मैं घर जा रहा हूँ", "मैं घर जा रही हूँ")
        self.assertAlmostEqual(result["wer"], 0.2)

    def test_insertion_and_deletion(self):
        added = textnorm.wer("मैं घर जाऊँगा", "मैं आज घर जाऊँगा")
        dropped = textnorm.wer("मैं आज घर जाऊँगा", "मैं घर जाऊँगा")
        self.assertAlmostEqual(added["wer"], 1 / 3)
        self.assertAlmostEqual(dropped["wer"], 0.25)

    def test_empty_hypothesis_is_total_loss(self):
        self.assertEqual(textnorm.wer("तीन शब्द यहाँ", "")["wer"], 1.0)

    def test_cer_below_wer_for_near_miss(self):
        result = textnorm.wer("शानदार नज़ारा", "शानदार नजारा")
        self.assertGreater(result["wer"], result["cer"])


class NumeralRecallTest(unittest.TestCase):
    def test_none_when_no_numbers(self):
        self.assertIsNone(textnorm.numeral_recall("कोई संख्या नहीं", "कोई संख्या नहीं"))

    def test_full_and_partial(self):
        self.assertEqual(textnorm.numeral_recall("दस और तीस", "दस और तीस"), 1.0)
        self.assertEqual(textnorm.numeral_recall("दस और तीस", "दस और"), 0.5)
        self.assertEqual(textnorm.numeral_recall("दस और तीस", "बीस और चालीस"), 0.0)


class SpokenTest(unittest.TestCase):
    def test_digits_become_hindi_words(self):
        spoken = textnorm.to_spoken("कंपनी ने 24,07,000 रुपये कमाए।")
        self.assertEqual(spoken, "कंपनी ने चौबीस लाख सात हज़ार रुपये कमाए.")

    def test_spoken_and_digits_score_identically(self):
        reference = "कंपनी ने 24,07,000 रुपये कमाए।"
        spoken = textnorm.to_spoken(reference)
        self.assertEqual(textnorm.wer(reference, spoken)["wer"], 0.0)
        self.assertEqual(textnorm.numeral_recall(reference, spoken), 1.0)

    def test_danda_and_zwj(self):
        self.assertEqual(textnorm.to_spoken("हाँ। नहीं॥"), "हाँ. नहीं.")
        self.assertEqual(textnorm.to_spoken("क्\u200dष"), "क्ष")

    def test_devanagari_digits(self):
        self.assertEqual(textnorm.to_spoken("२०२६"), "दो हज़ार छब्बीस")

    def test_decimal_and_percent(self):
        self.assertEqual(textnorm.to_spoken("3.5%"), "तीन दशमलव पाँच प्रतिशत")

    def test_lakh_and_crore_grouping(self):
        self.assertEqual(textnorm.int_to_hindi_words(2407000),
                         "चौबीस लाख सात हज़ार")
        self.assertEqual(textnorm.int_to_hindi_words(22000000),
                         "दो करोड़ बीस लाख")
        self.assertEqual(textnorm.int_to_hindi_words(0), "शून्य")

    def test_expansion_can_be_disabled(self):
        self.assertEqual(textnorm.to_spoken("24 रुपये", expand_numbers=False),
                         "24 रुपये")


class DevanagariTest(unittest.TestCase):
    def test_detects_hindi(self):
        self.assertTrue(textnorm.is_devanagari("नमस्ते"))
        self.assertFalse(textnorm.is_devanagari("hello"))


if __name__ == "__main__":
    unittest.main()

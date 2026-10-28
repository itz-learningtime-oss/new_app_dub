"""Hindi text normalization plus WER/CER and numeral checks.

Stdlib only: the scorer must run anywhere, including before torch is installed.

Two ideas matter here:

1. ``normalize_for_wer`` removes punctuation/zero-width marks and rewrites both
   digit groups and Hindi number words as ``<num:N>`` tokens, so "24,07,000"
   and "चौबीस लाख सात हज़ार" compare as the same number instead of as four
   spurious word errors.
2. ``extract_numerals`` keeps a separate, human-readable numeral recall metric
   ("did the model actually say 2407000?"), because <num:N> equality can hide a
   dropped digit.
"""

from __future__ import annotations

import re
import unicodedata

DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
ZERO_WIDTH = dict.fromkeys(map(ord, "\u200b\u200c\u200d\u2060\ufeff"), None)

_UNITS = {
    "शून्य": 0, "एक": 1, "दो": 2, "तीन": 3, "चार": 4, "पाँच": 5, "पांच": 5,
    "छह": 6, "छः": 6, "सात": 7, "आठ": 8, "नौ": 9, "दस": 10,
    "ग्यारह": 11, "बारह": 12, "तेरह": 13, "चौदह": 14, "पंद्रह": 15,
    "सोलह": 16, "सत्रह": 17, "अठारह": 18, "उन्नीस": 19,
    "बीस": 20, "इक्कीस": 21, "बाईस": 22, "तेईस": 23, "चौबीस": 24,
    "पच्चीस": 25, "छब्बीस": 26, "सत्ताईस": 27, "अट्ठाईस": 28, "उनतीस": 29,
    "तीस": 30, "इकतीस": 31, "बत्तीस": 32, "तैंतीस": 33, "चौंतीस": 34,
    "पैंतीस": 35, "छत्तीस": 36, "सैंतीस": 37, "अड़तीस": 38, "उनतालीस": 39,
    "चालीस": 40, "इकतालीस": 41, "बयालीस": 42, "तैंतालीस": 43,
    "चवालीस": 44, "पैंतालीस": 45, "छियालीस": 46, "सैंतालीस": 47,
    "अड़तालीस": 48, "उनचास": 49,
    "पचास": 50, "इक्यावन": 51, "बावन": 52, "तिरपन": 53, "चौवन": 54,
    "पचपन": 55, "छप्पन": 56, "सत्तावन": 57, "अट्ठावन": 58, "उनसठ": 59,
    "साठ": 60, "इकसठ": 61, "बासठ": 62, "तिरसठ": 63, "चौंसठ": 64,
    "पैंसठ": 65, "छियासठ": 66, "सड़सठ": 67, "अड़सठ": 68, "उनहत्तर": 69,
    "सत्तर": 70, "इकहत्तर": 71, "बहत्तर": 72, "तिहत्तर": 73, "चौहत्तर": 74,
    "पचहत्तर": 75, "छिहत्तर": 76, "सतहत्तर": 77, "अठहत्तर": 78, "उनासी": 79,
    "अस्सी": 80, "इक्यासी": 81, "बयासी": 82, "तिरासी": 83, "चौरासी": 84,
    "पचासी": 85, "छियासी": 86, "सत्तासी": 87, "अट्ठासी": 88, "नवासी": 89,
    "नब्बे": 90, "इक्यानवे": 91, "बानवे": 92, "तिरानवे": 93,
    "चौरानवे": 94, "पचानवे": 95, "छियानवे": 96, "सत्तानवे": 97,
    "अट्ठानवे": 98, "निन्यानवे": 99,
}

_MULTIPLIERS = {
    "सौ": 100,
    "हज़ार": 1_000,
    "हजार": 1_000,
    "लाख": 100_000,
    "करोड़": 10_000_000,
    "करोड": 10_000_000,
}

_DIGIT_RUN = re.compile(r"\d[\d,]*")

# value -> one canonical Devanagari spelling, used when *speaking* numbers.
# _UNITS holds several spellings per value; the first one wins ("पाँच", "छह").
_UNIT_WORDS: dict[int, str] = {}
for _word, _value in _UNITS.items():
    _UNIT_WORDS.setdefault(_value, _word)
del _word, _value

_GROUPING_COMMA = re.compile(r"(?<=\d),(?=\d)")
_DECIMAL_RUN = re.compile(r"\d+(?:\.\d+)?")


def int_to_hindi_words(value: int) -> str:
    """1234567 -> "बारह लाख चौंतीस हज़ार पाँच सौ सड़सठ" (Indian grouping)."""
    if value < 0:
        return "माइनस " + int_to_hindi_words(-value)
    if value == 0:
        return _UNIT_WORDS[0]

    parts: list[str] = []
    remainder = int(value)
    for scale, word in ((10_000_000, "करोड़"), (100_000, "लाख"), (1_000, "हज़ार")):
        if remainder >= scale:
            count, remainder = divmod(remainder, scale)
            parts.append(f"{int_to_hindi_words(count)} {word}")
    if remainder >= 100:
        hundreds, remainder = divmod(remainder, 100)
        parts.append("सौ" if hundreds == 1 else f"{_UNIT_WORDS[hundreds]} सौ")
    if remainder:
        parts.append(_UNIT_WORDS[remainder])
    return " ".join(parts)


def _strip_marks(text: str) -> str:
    text = unicodedata.normalize("NFC", text)
    return text.translate(ZERO_WIDTH)


def words(text: str) -> list[str]:
    return [w for w in text.split() if w]


def _parse_number_words(tokens: list[str], start: int) -> tuple[int, int] | None:
    """Greedy numeral phrase parser. Returns (value, tokens_consumed)."""
    total = 0
    current = 0
    consumed = 0
    saw_any = False

    i = start
    while i < len(tokens):
        token = tokens[i]
        if token in _MULTIPLIERS:
            total += max(current, 1) * _MULTIPLIERS[token]
            current = 0
            saw_any = True
            consumed += 1
            i += 1
            continue
        if token in _UNITS:
            current += _UNITS[token]
            saw_any = True
            consumed += 1
            i += 1
            continue
        # "पंद्रह सौ" style: value then multiplier already handled above.
        break

    if not saw_any:
        return None
    return total + current, consumed


def extract_numerals(text: str) -> list[int]:
    """All numbers in ``text`` in reading order, from digits or Hindi words."""
    text = _strip_marks(text).translate(DEVANAGARI_DIGITS)
    found: list[int] = []

    for token in words(text):
        match = _DIGIT_RUN.fullmatch(token.strip(".,"))
        if match:
            try:
                found.append(int(match.group(0).replace(",", "")))
            except ValueError:
                pass

    tokens = words(text)
    i = 0
    while i < len(tokens):
        parsed = _parse_number_words(tokens, i)
        if parsed is None:
            i += 1
            continue
        value, consumed = parsed
        # A lone numeral word can be a false positive ("एक बार" = "once").
        found.append(value)
        i += consumed

    return found


def canonicalize_numbers(text: str) -> str:
    """Rewrite digit runs and Hindi numeral phrases as ``<num:N>``."""
    text = _strip_marks(text).translate(DEVANAGARI_DIGITS)

    def replace_digits(match: re.Match[str]) -> str:
        raw = match.group(0).replace(",", "")
        return f" <num:{int(raw)}> "

    text = _DIGIT_RUN.sub(replace_digits, text)

    tokens = words(text)
    out: list[str] = []
    i = 0
    while i < len(tokens):
        parsed = _parse_number_words(tokens, i)
        if parsed is None:
            out.append(tokens[i])
            i += 1
            continue
        value, consumed = parsed
        out.append(f"<num:{value}>")
        i += consumed

    return " ".join(out)


def _say_number(match: re.Match[str]) -> str:
    raw = match.group(0)
    if "." in raw:
        whole, fraction = raw.split(".", 1)
        digits = " ".join(_UNIT_WORDS[int(d)] for d in fraction if d.isdigit())
        return f"{int_to_hindi_words(int(whole))} दशमलव {digits}" if digits else int_to_hindi_words(int(whole))
    return int_to_hindi_words(int(raw))


def to_spoken(text: str, expand_numbers: bool = True) -> str:
    """The form handed to the TTS engine (as opposed to the WER reference).

    Chatterbox reads digit strings badly (`24,07,000` came out as "24 देसत दार
    उपे"), so digits are spelled out in Hindi before synthesis. Danda becomes a
    plain period because the library's punctuation normaliser does not know
    Devanagari sentence marks.
    """
    text = _strip_marks(text).translate(DEVANAGARI_DIGITS)
    text = text.replace("॥", ".").replace("।", ".")
    if expand_numbers:
        text = _GROUPING_COMMA.sub("", text)
        text = text.replace("%", " प्रतिशत ").replace("₹", " ")
        text = _DECIMAL_RUN.sub(_say_number, text)
    return " ".join(text.split())


def normalize_for_wer(text: str) -> str:
    """Punctuation-free, numeral-canonical form used for WER/CER."""
    text = canonicalize_numbers(text)
    text = unicodedata.normalize("NFC", text)

    kept: list[str] = []
    for ch in text:
        category = unicodedata.category(ch)
        if category.startswith("P") or category.startswith("S"):
            kept.append(" ")
        elif category == "Cf":
            continue
        else:
            kept.append(ch)

    compact = " ".join("".join(kept).split())
    return compact.casefold() if compact.isascii() else compact


def _edit_distance(ref: list[str], hyp: list[str]) -> int:
    """Levenshtein distance with substitutions/insertions/deletions."""
    if not ref:
        return len(hyp)
    previous = list(range(len(hyp) + 1))
    for i, ref_item in enumerate(ref, start=1):
        current = [i] + [0] * len(hyp)
        for j, hyp_item in enumerate(hyp, start=1):
            cost = 0 if ref_item == hyp_item else 1
            current[j] = min(
                previous[j] + 1,          # deletion
                current[j - 1] + 1,       # insertion
                previous[j - 1] + cost,   # substitution
            )
        previous = current
    return previous[-1]


def wer(reference: str, hypothesis: str) -> dict[str, float]:
    """Word/character error rate plus the raw counts, punctuation-insensitive."""
    ref_words = words(normalize_for_wer(reference))
    hyp_words = words(normalize_for_wer(hypothesis))
    ref_chars = list("".join(ref_words))
    hyp_chars = list("".join(hyp_words))

    word_edits = _edit_distance(ref_words, hyp_words)
    char_edits = _edit_distance(ref_chars, hyp_chars)

    word_denom = max(1, len(ref_words))
    char_denom = max(1, len(ref_chars))
    return {
        "wer": word_edits / word_denom,
        "cer": char_edits / char_denom,
        "word_edits": float(word_edits),
        "word_count": float(len(ref_words)),
        "char_edits": float(char_edits),
        "char_count": float(len(ref_chars)),
    }


def numeral_recall(reference: str, hypothesis: str) -> float | None:
    """Fraction of reference numerals that appear in the hypothesis.

    ``None`` when the reference has no numerals, so the metric does not drag
    non-numeric sentences into the average.
    """
    expected = extract_numerals(reference)
    if not expected:
        return None
    heard = set(extract_numerals(hypothesis))
    hits = sum(1 for value in expected if value in heard)
    return hits / len(expected)


def is_devanagari(text: str) -> bool:
    return any("\u0900" <= ch <= "\u097f" for ch in text)

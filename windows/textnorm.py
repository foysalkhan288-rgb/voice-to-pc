"""Output transforms for the phone's transcripts, shared by preview and final text.

The phone sends raw recognized text plus the chosen output mode; the PC applies
the format here so the preview and the typed text always match.
"""
from __future__ import annotations

import re
import unicodedata

_BENGALI_RUN = re.compile("[\u0980-\u09FF]+")

_CONSONANTS = {
    "ক": "k", "খ": "kh", "গ": "g", "ঘ": "gh", "ঙ": "ng",
    "চ": "ch", "ছ": "chh", "জ": "j", "ঝ": "jh", "ঞ": "ny",
    "ট": "t", "ঠ": "th", "ড": "d", "ঢ": "dh", "ণ": "n",
    "ত": "t", "থ": "th", "দ": "d", "ধ": "dh", "ন": "n",
    "প": "p", "ফ": "f", "ব": "b", "ভ": "bh", "ম": "m",
    "য": "j", "র": "r", "ল": "l", "শ": "sh", "ষ": "sh",
    "স": "s", "হ": "h", "ড়": "r", "ঢ়": "rh", "য়": "y",
    "ৎ": "t",
}
_INDEPENDENT_VOWELS = {
    "অ": "o", "আ": "a", "ই": "i", "ঈ": "i", "উ": "u",
    "ঊ": "u", "ঋ": "ri", "এ": "e", "ঐ": "oi", "ও": "o", "ঔ": "ou",
}
_VOWEL_SIGNS = {
    "া": "a", "ি": "i", "ী": "i", "ু": "u", "ূ": "u",
    "ৃ": "ri", "ে": "e", "ৈ": "oi", "ো": "o", "ৌ": "ou",
}
_NASALS = {"ং": "ng", "ঃ": "h", "ঁ": "n"}

# NFC never composes these (Unicode composition exclusions), so do it explicitly.
_NUKTA_LETTERS = {
    chr(0x09A1) + chr(0x09BC): chr(0x09DC),
    chr(0x09A2) + chr(0x09BC): chr(0x09DD),
    chr(0x09AF) + chr(0x09BC): chr(0x09DF),
}


def _transliterate_word(word: str) -> str:
    result: list[str] = []
    index = 0
    trailing_inherent_vowel = False
    while index < len(word):
        char = word[index]
        nasal = _NASALS.get(char)
        if nasal is not None:
            result.append(nasal)
            trailing_inherent_vowel = False
            index += 1
            continue
        consonant = _CONSONANTS.get(char)
        if consonant is not None:
            result.append(consonant)
            index += 1
            trailing_inherent_vowel = False
            if index < len(word) and word[index] in _VOWEL_SIGNS:
                result.append(_VOWEL_SIGNS[word[index]])
                index += 1
            elif index < len(word) and word[index] == "্":
                index += 1
            else:
                result.append("o")
                trailing_inherent_vowel = True
            continue
        vowel = _INDEPENDENT_VOWELS.get(char)
        if vowel is not None:
            result.append(vowel)
            trailing_inherent_vowel = False
        else:
            sign = _VOWEL_SIGNS.get(char)
            if sign is not None:
                result.append(sign)
                trailing_inherent_vowel = False
            else:
                result.append(char)
                trailing_inherent_vowel = False
        index += 1
    text = "".join(result)
    if trailing_inherent_vowel and text.endswith("o"):
        text = text[:-1]
    return text


def romanize(text: str) -> str:
    return _BENGALI_RUN.sub(lambda m: _transliterate_word(m.group(0)), text)


def normalize_english(text: str) -> str:
    out = romanize(text)
    out = re.sub(r"\s+", " ", out).strip()
    if not out:
        return out
    return out[0].upper() + out[1:]


def transform(text: str, output_mode: str) -> str:
    # Recognizers may emit decomposed Bangla (base + nukta); compose before mapping.
    text = unicodedata.normalize("NFC", text)
    for decomposed, composed in _NUKTA_LETTERS.items():
        text = text.replace(decomposed, composed)
    if output_mode == "roman":
        return romanize(text)
    if output_mode == "english":
        return normalize_english(text)
    return text
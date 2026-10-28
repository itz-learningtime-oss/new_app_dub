# Phase 0 — Hindi voice-cloning bake-off

Status: **first sweep done (Config B, defaults, spoken numbers); T3 v3 + Hindi finetune pending.**

Last updated: 2026-10-28

## Question

Which engine should render Hindi in Hush Studio without giving up quality?

Today the app ships `voxcpm-0.5b-q4_k.gguf`, which is Chinese + English only. The
tokenizer falls back to byte tokens for Devanagari (`vendor/voxcpm/src/tokenizer.cpp`),
so Hindi input produces gibberish. We need evidence, not intuition, before we
change the model, the ASR, the sentence splitter and the UI copy around it.

## Method

- One reference clip of the target speaker: 8–12 s, mono, clean, peak-normalised.
  Recorded once, reused by every candidate (hash recorded in `out/manifest.json`).
- One frozen sentence set: `phase0/sentences_hi.json` v1, 10 coverage sentences
  (declarative, digit grouping `24,07,000`, number words `पंद्रह सौ`, date/time,
  English loanwords, long compound, question, exclamation, proper nouns,
  multi-clause danda) plus 2 stress sentences (long paragraph, code-mix).
- One seed (`4242`) and one decode recipe per candidate.
- Identical scoring code for every candidate (`phase0/bakeoff/scoring.py`).
- Numbers are compared twice: canonicalised in WER (`24,07,000` ≡ `चौबीस लाख सात हज़ार`)
  and as raw numeral recall, so a dropped digit cannot hide inside a good score.

## Candidates

| Key | Model | On-device | Why it is here |
|-----|-------|-----------|----------------|
| `cb_mtl_configB` | Chatterbox Multilingual + Config B | yes | native Hindi, MIT, GGUF path exists |
| `cb_mtl_default` | Chatterbox MTL, library defaults | yes | control: does Config B actually matter |
| `cb_mtl_configB_spoken` | as above + spoken numbers | yes | isolates digit→Hindi-word expansion |
| `cb_mtl_v3_configB` | T3 **v3** pinned + Config B | yes | `from_pretrained()` defaults to v2 |
| `cb_hi_configB` | Chatterbox Hindi pack + Config B + spoken numbers | yes | official `hi` finetune |
| `indicf5` | IndicF5 337M | no | quality ceiling; needs exact transcript |
| `voxcpm2` | VoxCPM2 2B | no | control; Hindi is its weakest language |
| `gguf_mtl_q8` | Chatterbox MTL GGUF Q8 | yes | proves the Android runtime |
| `gguf_mtl_f16` | Chatterbox MTL GGUF F16 | yes | quantisation-loss reference |

Config B: `exaggeration 0.7`, `temperature 0.6`, `min_p 0.1` (Praxy Voice
recipe), `cfg_weight` untouched at the library default.

## Metrics and bars

| Metric | Source | Bar |
|--------|--------|-----|
| WER ↓ | faster-whisper `large-v3`, Hindi | ≤ 15 % |
| WER median | per-sentence, outlier-insensitive | reported |
| CER ↓ | same transcript, punctuation-insensitive | **≤ 6 %** (primary) |
| CER corpus | length-weighted character edits | reported |
| Numeral recall ↑ | `textnorm.numeral_recall` | 100 % (with spoken numbers) |
| Speaker SIM ↑ | ECAPA-TDNN (`spkrec-ecapa-voxceleb`) | ≥ 0.75 |
| UTMOS22 ↑ | `tarepan/SpeechMOS` (`utmos22_strong`) | ≥ 3.5 when available |
| RTF ↓ | measured wall time ÷ audio seconds | reported |
| Blind preference | seeded A/B sheet, human listener | ≥ 60 % |

Rationale for the 2026-10-28 revision: the first sweep landed at WER 23.9 % /
CER 7.9 %, i.e. most misses are one or two characters in an otherwise right
word. CER is therefore the intelligibility verdict, WER guards against whole-word
collapse, and any single sentence above 100 % WER (hallucinated tail) is flagged
as `runaway` instead of silently poisoning the mean.

Decision rule: ship the best **on-device-capable** candidate that clears the
bars. If only `indicf5` clears them, Hindi must stay off-device and the UI has
to say so.

## Reproduce

```bash
python phase0/run.py check --reference hindi_10s.wav
python phase0/run.py all --reference hindi_10s.wav --ref-text "…" --publish
```

Details, environment variables and artifact layout: `phase0/README.md`.

<!-- RESULTS:START -->
_Not run yet._
<!-- RESULTS:END -->

## Follow-ups unlocked by the result

- Phase 1: wire the winning engine into `bridge.cpp` and the mobile session with
  a per-model manifest (`StudioStore.kt`), instead of one pinned SHA.
- Phase 2: Hindi ASR for reference transcription + `।`/`॥` sentence splitting
  (`AudioFiles.ScriptSections.split`), optional transcripts in `VoiceLibrary`.
- Phase 3: NDK build, arm64 device test, storage/RAM admission for a larger model.

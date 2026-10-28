# Phase 0 — Hindi voice-cloning bake-off

Quality-first experiment that decides which engine ships for Hindi. Nothing in
the Android app changes until this produces a table.

## Why

The pinned `voxcpm-0.5b-q4_k.gguf` was trained on Chinese + English, so
Devanagari hits the tokenizer's byte fallback (`<0xE0>…`) and comes out as
gibberish. Hindi needs either different weights or a different model, and we are
not guessing which: we measure.

## Rules

- One fixed reference clip (8–12 s, mono, clean, same speaker we care about).
- One fixed 12-sentence set (`sentences_hi.json`, versioned).
- One seed (`4242`), one decode recipe per candidate, config recorded in
  `out/manifest.json`.
- Every candidate gets the same sentences and the same scoring code.
- Never edit a sentence in place; bump `version` and re-run everything.

## Quickstart (local sanity check)

```bash
python phase0/run.py sentences
python phase0/run.py check --reference path/to/hindi_10s.wav
python phase0/run.py render --reference path/to/hindi_10s.wav --dry-run
```

## Full run (Colab GPU recommended)

```bash
pip install -r phase0/requirements.txt
python phase0/run.py doctor          # must print "OK  stack looks consistent"
```

`chatterbox-tts` pins `torch==2.6.0` / `torchaudio==2.6.0` / `numpy<2`, while
Colab ships a newer torch with a cu130 torchvision. If pip's resolver leaves them
mismatched you get `torchvision requires torch==2.11.0, but you have torch 2.6.0`
and imports fail. Repair once per fresh runtime, then restart:

```bash
# numpy pin depends on the Python version: 1.26.4 for <=3.12, 2.2.6 for 3.13+
pip install --force-reinstall "numpy==2.2.6" torch==2.6.0 torchvision==0.21.0 \
  torchaudio==2.6.0 --extra-index-url https://download.pytorch.org/whl/cu124
# Runtime -> Restart session, then re-check
python phase0/run.py doctor
```

The `gcsfs` / `starlette` / `google-adk` / `datasets` / `ipython` conflict lines in
pip output are Colab's own packages and do not affect this run.

Then:

```bash
python phase0/run.py all \
  --reference /content/drive/MyDrive/audio_automation/hindi_10s.wav \
  --ref-text "the exact words spoken in the clip" \
  --out phase0/out
```

`--ref-text` is mandatory only for IndicF5 and used by VoxCPM2's prompt mode.
Get it from the automatic transcript, correct it by hand, then re-render.

IndicF5 needs `transformers` 4.x, which conflicts with chatterbox's 5.2.0 pin, so
run it in a second session (a runtime restart keeps `phase0/out`):

```bash
pip install "transformers==4.46.3" vocos   # then restart the runtime
python phase0/run.py render --reference ref.wav --ref-text "…" --candidates indicf5
python phase0/run.py score                 # re-scores every wav it finds
python phase0/run.py report
```

Run one candidate at a time when experimenting:

```bash
python phase0/run.py render --reference ref.wav --candidates cb_mtl_configB
python phase0/run.py score
python phase0/run.py report --publish
```

## Commands

| Command | Purpose |
|---------|---------|
| `doctor` | check the installed stack, torch trio and GPU |
| `sentences` | print the frozen test set |
| `check` | validate a reference clip (length, level, silence, SHA-256) |
| `render` | synthesize every candidate/sentence, resumable |
| `score` | Hindi ASR → WER/CER, ECAPA speaker SIM, UTMOS22, RTF |
| `report` | aggregate table; `--publish` writes into `docs/phase0-hindi-bakeoff.md` |
| `blind` | seeded, order-shuffled blind A/B HTML + rating CSV |
| `all` | check → render → score → report |

`render` skips existing WAVs (use `--force` to redo), appends timings to
`out/timings.csv` after every sentence, and isolates failures per candidate: a
gated or missing model is reported and the run continues.

## Environment variables

| Variable | Used by | Meaning |
|----------|---------|---------|
| `PHASE0_OUT` | all | output directory (default `phase0/out`) |
| `PHASE0_DEVICE` | all | `cuda` or `cpu` (default: auto) |
| `PHASE0_CACHE` | score | model cache dir |
| `PHASE0_ASR_MODEL` | score | faster-whisper model (default `large-v3`) |
| `PHASE0_CB_HI_DIR` | `cb_hi_configB` | file or dir holding a pre-downloaded `t3_hi.safetensors` |
| `PHASE0_NO_S3GEN_SWAP` | `cb_hi_configB`, `cb_mtl_v3_configB` | keep the base S3Gen decoder instead of loading `s3gen_v3.pt` |
| `PHASE0_TTS_CLI` | `gguf_*` | codec.cpp `tts-cli` binary |
| `PHASE0_CB_Q8_BACKBONE` / `PHASE0_CB_Q8_CODEC` | `gguf_mtl_q8` | GGUF pair |
| `PHASE0_CB_F16_BACKBONE` / `PHASE0_CB_F16_CODEC` | `gguf_mtl_f16` | GGUF pair |
| `PHASE0_TTS_EXTRA` | `gguf_*` | extra CLI flags |
| `PHASE0_INDICF5_SR` | `indicf5` | output sample rate if the model does not report it |
| `PHASE0_DOC` | `report --publish` | markdown file to update (default `docs/phase0-hindi-bakeoff.md`) |
| `HF_TOKEN` | gated repos | Hugging Face token |

## Candidates

| Key | Model | On-device | Notes |
|-----|-------|-----------|-------|
| `cb_mtl_configB` | Chatterbox MTL, Config B | yes | primary target |
| `cb_mtl_default` | same, library defaults | yes | control for the recipe |
| `cb_mtl_configB_spoken` | as above + spoken numbers | yes | isolates digit→Hindi-word expansion |
| `cb_mtl_v3_configB` | T3 v3 pinned + Config B | yes | `from_pretrained()` defaults to v2 |
| `cb_hi_configB` | Chatterbox Hindi pack + Config B + spoken numbers | yes | official `hi` finetune |
| `indicf5` | IndicF5 337M | no | quality ceiling; needs exact transcript |
| `voxcpm2` | VoxCPM2 2B | no | control; Hindi is its weakest language |
| `gguf_mtl_q8` | Chatterbox MTL GGUF Q8 | yes | Android-runtime check via codec.cpp |
| `gguf_mtl_f16` | same at F16 | yes | quantisation-loss reference |

## Metrics and decision rule

| Metric | Why | Bar |
|--------|-----|-----|
| WER (Hindi ASR) | intelligibility, the thing users notice | ≤ 15 % |
| WER median | one runaway sentence must not decide the ranking | reported |
| CER | catches near-miss word forms (the common failure) | ≤ 6 % |
| Numeral recall | numbers are where TTS breaks | 100 % |
| Speaker SIM (ECAPA) | is it still the cloned voice | ≥ 0.75 |
| UTMOS22 | naturalness / prosody | ≥ 3.5 when it loads |
| RTF | phone feasibility | reported per candidate |
| Blind A/B | final human tie-break | ≥ 60 % preference |

A single sentence above 100 % WER (hallucinated tail) is reported as `runaway`
rather than hidden in the average.

Decision: the best **on-device-capable** candidate that clears the bars ships.
If no on-device candidate clears them but `indicf5` does, Hindi stays
off-device and the app must say so instead of pretending.

## Artifacts

```
phase0/out/
  manifest.json        seed, config, reference hash, candidate list
  reference.wav        peak-normalised copy used by every render
  timings.csv          wall time per sentence (source of RTF)
  <candidate>/<id>.wav one clip per sentence
  results.csv          per-sentence scores + ASR transcript
  _score_cache.json    ASR/SIM/UTMOS per WAV hash (re-scoring is seconds)
  report.md            decision table
  blind_ab.html        human A/B sheet
  blind_ratings.csv    slot → candidate mapping (do not peek before rating)
```

`out/` is git-ignored: audio and CSV stay local, only the report is committed.

## Tests

The harness logic runs without any ML dependency:

```bash
python -m unittest discover -s phase0/tests -t phase0
```

45 tests cover Hindi numeral parsing (`24,07,000` ≡ `चौबीस लाख सात हज़ार`),
digit→word expansion, WER/CER, reference validation, resampling, aggregation,
report publishing and the blind A/B sheet.

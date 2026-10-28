"""Fixed configuration for the Phase 0 Hindi cloning bake-off.

Everything that influences a render lives here so results stay comparable
between runs, machines and people. Bump SENTENCE_SET_VERSION when the test
set changes and re-run every candidate.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(os.path.abspath(__file__)).parent.parent
SENTENCES_FILE = ROOT / "sentences_hi.json"
DEFAULT_OUT = ROOT / "out"

# Decoding is stochastic; one seed for every candidate keeps A/B honest.
SEED = 4242

# Praxy Voice "Config B" (arXiv 2604.25441): the recipe that took vanilla
# Chatterbox to commercial-class Hindi intelligibility. cfg_weight is left at
# the Chatterbox default because the paper does not change it.
CONFIG_B = {
    "exaggeration": 0.7,
    "temperature": 0.6,
    "min_p": 0.1,
}
CHATTERBOX_DEFAULTS = {
    "exaggeration": 0.5,
    "temperature": 0.8,
    "min_p": 0.05,
}
CFG_WEIGHT = 0.5
TTS_LANGUAGE_ID = "hi"

# VoxCPM2 control run settings (same as the CLI defaults used by the app).
VOXCPM2_SETTINGS = {
    "cfg_value": 2.0,
    "inference_timesteps": 10,
}

REFERENCE_MIN_SECONDS = 5.0
REFERENCE_MAX_SECONDS = 15.0
REFERENCE_TARGET_SECONDS = 10.0


@dataclass(frozen=True)
class Candidate:
    key: str
    label: str
    family: str  # chatterbox | indicf5 | voxcpm2 | gguf
    variant: str
    on_device: bool
    needs_ref_text: bool
    notes: str
    spoken_numbers: bool = False      # spell digits out before synthesis
    t3_model: str | None = None       # pin a T3 checkpoint ("v2" / "v3")


CANDIDATES: tuple[Candidate, ...] = (
    Candidate(
        key="cb_mtl_configB",
        label="Chatterbox Multilingual + Config B",
        family="chatterbox",
        variant="mtl_configB",
        on_device=True,
        needs_ref_text=False,
        notes="Primary on-device target. 23 languages, Hindi native, MIT.",
    ),
    Candidate(
        key="cb_mtl_default",
        label="Chatterbox Multilingual (library defaults)",
        family="chatterbox",
        variant="mtl_default",
        on_device=True,
        needs_ref_text=False,
        notes="Control: measures how much Config B actually buys us.",
    ),
    Candidate(
        key="cb_mtl_configB_spoken",
        label="Chatterbox Multilingual + Config B + spoken numbers",
        family="chatterbox",
        variant="mtl_configB",
        on_device=True,
        needs_ref_text=False,
        spoken_numbers=True,
        notes="Isolates digit->Hindi-word expansion; only n02 changes.",
    ),
    Candidate(
        key="cb_mtl_v3_configB",
        label="Chatterbox Multilingual T3 v3 + Config B",
        family="chatterbox",
        variant="mtl_configB",
        on_device=True,
        needs_ref_text=False,
        t3_model="v3",
        notes="Isolates the T3 checkpoint v2 vs v3 (library default is v2).",
    ),
    Candidate(
        key="cb_hi_configB",
        label="Chatterbox Hindi finetune + Config B + spoken numbers",
        family="chatterbox",
        variant="hi_configB",
        on_device=True,
        needs_ref_text=False,
        spoken_numbers=True,
        notes="Dedicated hi pack (ResembleAI/Chatterbox-Multilingual-hi), assembled locally.",
    ),
    Candidate(
        key="indicf5",
        label="IndicF5 337M (off-device quality ceiling)",
        family="indicf5",
        variant="teacher",
        on_device=False,
        needs_ref_text=True,
        notes="MIT. Requires the exact reference transcript. No ggml runtime.",
    ),
    Candidate(
        key="voxcpm2",
        label="VoxCPM2 2B (control)",
        family="voxcpm2",
        variant="f16",
        on_device=False,
        needs_ref_text=False,
        notes="Hindi is one of its weakest languages per its own report.",
    ),
    Candidate(
        key="gguf_mtl_q8",
        label="Chatterbox MTL GGUF Q8 (runtime check)",
        family="gguf",
        variant="q8",
        on_device=True,
        needs_ref_text=False,
        notes="Same weights as cb_mtl_* through llama.cpp + codec.cpp.",
    ),
    Candidate(
        key="gguf_mtl_f16",
        label="Chatterbox MTL GGUF F16 (runtime check)",
        family="gguf",
        variant="f16",
        on_device=True,
        needs_ref_text=False,
        notes="Quantization-loss reference for the Q8 build.",
    ),
)

CANDIDATE_BY_KEY = {c.key: c for c in CANDIDATES}


def out_dir() -> Path:
    return Path(os.environ.get("PHASE0_OUT", DEFAULT_OUT))


def device() -> str:
    forced = os.environ.get("PHASE0_DEVICE")
    if forced:
        return forced
    try:
        import torch

        return "cuda" if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"

"""Aggregate results into a decision table, plus a seeded blind A/B sheet."""

from __future__ import annotations

import csv
import datetime as dt
import html
import os
import random
import statistics
import sys
from pathlib import Path

from . import cli_io, config

RESULTS_START = "<!-- RESULTS:START -->"
RESULTS_END = "<!-- RESULTS:END -->"
DOC_PATH = Path(os.environ.get(
    "PHASE0_DOC", config.ROOT.parent / "docs" / "phase0-hindi-bakeoff.md"
))

# Decision bar agreed after the first sweep: 23.9% WER / 7.9% CER means most
# misses are one or two characters (near-miss words), so CER carries the
# intelligibility verdict and WER guards against whole-word collapse.
ACCEPTANCE = {
    "wer": 0.15,
    "cer": 0.06,
    "speaker_sim": 0.75,
    "numeral_recall": 0.99,
    "utmos": 3.5,
}


def load_rows(out_dir: Path) -> list[dict]:
    path = out_dir / "results.csv"
    if not path.exists():
        raise SystemExit(f"missing {path}; run 'score' first")
    with path.open(encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def _number(row: dict, key: str) -> float | None:
    raw = row.get(key, "")
    if raw in ("", None):
        return None
    try:
        return float(raw)
    except ValueError:
        return None


def aggregate(rows: list[dict]) -> list[dict]:
    by_candidate: dict[str, list[dict]] = {}
    for row in rows:
        by_candidate.setdefault(row["candidate"], []).append(row)

    summary: list[dict] = []
    for key, items in by_candidate.items():
        candidate = config.CANDIDATE_BY_KEY.get(key)
        entry: dict = {
            "key": key,
            "label": candidate.label if candidate else key,
            "n": len(items),
            "on_device": candidate.on_device if candidate else None,
        }
        for metric in ("wer", "cer", "speaker_sim", "utmos", "numeral_recall", "rtf"):
            values = [v for v in (_number(row, metric) for row in items) if v is not None]
            entry[metric] = sum(values) / len(values) if values else None
            entry[f"{metric}_median"] = statistics.median(values) if values else None
        for metric in ("wer", "cer"):
            values = [v for v in (_number(row, metric) for row in items) if v is not None]
            entry[f"{metric}_max"] = max(values) if values else None
        entry["wer_corpus"] = _corpus(items, "word")
        entry["cer_corpus"] = _corpus(items, "char")
        summary.append(entry)

    summary.sort(key=lambda e: (e["wer"] if e["wer"] is not None else 9.0,
                                -(e["speaker_sim"] or 0.0)))
    return summary


def _corpus(items: list[dict], kind: str) -> float | None:
    """Length-weighted error rate; the macro mean is hostage to one bad sentence."""
    edits = [v for v in (_number(row, f"{kind}_edits") for row in items) if v is not None]
    counts = [v for v in (_number(row, f"{kind}_count") for row in items) if v is not None]
    if not edits or not counts:
        return None
    total = sum(counts)
    return (sum(edits) / total) if total else None


def _fmt(value: float | None, digits: int = 2, percent: bool = False) -> str:
    if value is None:
        return "—"
    if percent:
        return f"{value * 100:.1f}%"
    return f"{value:.{digits}f}"


def _verdict(entry: dict) -> str:
    if entry["wer"] is None or entry["speaker_sim"] is None:
        return "no data"
    passed = (
        entry["wer"] <= ACCEPTANCE["wer"]
        and (entry["cer"] is None or entry["cer"] <= ACCEPTANCE["cer"])
        and entry["speaker_sim"] >= ACCEPTANCE["speaker_sim"]
    )
    # Numerals are only judged when the set actually contains some, and UTMOS
    # frequently fails to load in Colab (torch.hub); skipping it beats failing
    # every candidate for a missing nice-to-have.
    if entry.get("numeral_recall") is not None:
        passed = passed and entry["numeral_recall"] >= ACCEPTANCE["numeral_recall"]
    if entry.get("utmos") is not None:
        passed = passed and entry["utmos"] >= ACCEPTANCE["utmos"]
    verdict = "PASS" if passed else "below bar"
    if (entry.get("wer_max") or 0.0) > 1.0:
        verdict += " · runaway"  # hallucinated tail on a single sentence
    return verdict


def markdown_table(summary: list[dict]) -> str:
    lines = [
        "| # | Candidate | Device | WER ↓ | WER med | CER ↓ | CER corpus | Numeral ↑ | SIM ↑ | UTMOS ↑ | RTF ↓ | Verdict |",
        "|---|-----------|--------|-------|---------|-------|------------|-----------|-------|---------|-------|---------|",
    ]
    for index, entry in enumerate(summary, start=1):
        device = "yes" if entry["on_device"] else "no"
        lines.append(
            "| {i} | {label} | {device} | {wer} | {wermed} | {cer} | {cercorpus} | "
            "{num} | {sim} | {utmos} | {rtf} | {verdict} |".format(
                i=index,
                label=entry["label"],
                device=device,
                wer=_fmt(entry["wer"], 3, percent=True),
                wermed=_fmt(entry["wer_median"], 3, percent=True),
                cer=_fmt(entry["cer"], 3, percent=True),
                cercorpus=_fmt(entry["cer_corpus"], 3, percent=True),
                num=_fmt(entry["numeral_recall"], 2, percent=True),
                sim=_fmt(entry["speaker_sim"], 3),
                utmos=_fmt(entry["utmos"], 2),
                rtf=_fmt(entry["rtf"], 2),
                verdict=_verdict(entry),
            )
        )
    return "\n".join(lines)


def _checkpoint_notes(out_dir: Path) -> dict[str, str]:
    """Which T3/S3Gen the renders actually used (from timings.csv)."""
    path = out_dir / "timings.csv"
    if not path.exists():
        return {}
    notes: dict[str, str] = {}
    with path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            key, note = row.get("candidate", ""), (row.get("note") or "").strip()
            if key and note and key not in notes:
                notes[key] = note
    return notes


def build_report(out_dir: Path) -> str:
    rows = load_rows(out_dir)
    summary = aggregate(rows)
    manifest = {}
    manifest_path = out_dir / "manifest.json"
    if manifest_path.exists():
        manifest = cli_io.read_json(manifest_path)

    reference = manifest.get("reference", {})
    bullets = [
        f"- Generated: {dt.datetime.now().strftime('%Y-%m-%d %H:%M')}",
        f"- Seed: {manifest.get('seed', config.SEED)}",
        f"- Reference: `{Path(reference.get('path', '?')).name}` "
        f"({_fmt(reference.get('seconds'), 2)} s, {reference.get('sample_rate', '?')} Hz, "
        f"sha256 `{(reference.get('sha256') or '?')[:12]}…`)",
        f"- Sentences: {len({row['sentence_id'] for row in rows})}",
        f"- Acceptance bar: WER ≤ {ACCEPTANCE['wer']:.0%}, "
        f"CER ≤ {ACCEPTANCE['cer']:.0%}, SIM ≥ {ACCEPTANCE['speaker_sim']}, "
        f"numerals 100 %, UTMOS ≥ {ACCEPTANCE['utmos']} (when available)",
        "- WER med = median per sentence; CER corpus = character edits / characters "
        "(length-weighted, ignores one runaway sentence)",
    ]
    cache_path = out_dir / "_score_cache.json"
    if cache_path.exists():
        utmos_error = cli_io.read_json(cache_path).get("utmos_error")
        if utmos_error:
            bullets.append(f"- UTMOS unavailable ({utmos_error[:200]})")

    parts = ["## Results", "", *bullets, "", markdown_table(summary), ""]

    notes = _checkpoint_notes(out_dir)
    if notes:
        parts.append("### Loaded checkpoints")
        parts.append("")
        for key, note in notes.items():
            parts.append(f"- `{key}`: {note}")
        parts.append("")

    parts.append("### Per-sentence detail")
    parts.append("")
    parts.append("| Candidate | Sentence | WER | SIM | UTMOS | ASR heard |")
    parts.append("|-----------|----------|-----|-----|-------|-----------|")
    for row in rows:
        parts.append(
            "| {c} | {s} | {w} | {sim} | {u} | {t} |".format(
                c=row["candidate"],
                s=row["sentence_id"],
                w=_fmt(_number(row, "wer"), 3, percent=True),
                sim=_fmt(_number(row, "speaker_sim"), 3),
                u=_fmt(_number(row, "utmos"), 2),
                t=html.escape(row.get("transcript", ""))[:90],
            )
        )
    parts.append("")

    worst = [row for row in rows if (_number(row, "wer") or 0) > ACCEPTANCE["wer"]]
    parts.append("### Sentences over the WER bar")
    parts.append("")
    if worst:
        for row in worst[:20]:
            parts.append(
                f"- `{row['candidate']}` / `{row['sentence_id']}` "
                f"WER {_fmt(_number(row, 'wer'), 3, percent=True)} → "
                f"{html.escape(row.get('transcript', ''))}"
            )
    else:
        parts.append("- none")
    parts.append("")
    return "\n".join(parts)


def _emit(text: str) -> None:
    """Print even on a cp1252 console (Windows) without crashing the run."""
    stream = sys.stdout
    encoding = getattr(stream, "encoding", None) or "utf-8"
    try:
        print(text)
    except UnicodeEncodeError:
        stream.write(text.encode(encoding, "replace").decode(encoding, "replace") + "\n")
        stream.flush()


def write_report(out_dir: Path, publish: bool = False) -> Path:
    report = build_report(out_dir)
    target = out_dir / "report.md"
    target.write_text(report, encoding="utf-8")
    print(f"wrote {target}")
    print()
    _emit(report)

    if publish:
        if not DOC_PATH.exists():
            raise SystemExit(f"missing {DOC_PATH}")
        doc = DOC_PATH.read_text(encoding="utf-8")
        if RESULTS_START not in doc or RESULTS_END not in doc:
            raise SystemExit(f"{DOC_PATH} is missing the results markers")
        head, rest = doc.split(RESULTS_START, 1)
        _, tail = rest.split(RESULTS_END, 1)
        DOC_PATH.write_text(
            f"{head}{RESULTS_START}\n{report}\n{RESULTS_END}{tail}", encoding="utf-8"
        )
        print(f"published into {DOC_PATH}")
    return target


def blind_ab(out_dir: Path, key_a: str, key_b: str) -> Path:
    sentences = cli_io.read_json(config.SENTENCES_FILE)["sentences"]
    rng = random.Random(config.SEED)

    rows: list[dict] = []
    for sentence in sentences:
        wav_a = out_dir / key_a / f"{sentence['id']}.wav"
        wav_b = out_dir / key_b / f"{sentence['id']}.wav"
        if not wav_a.exists() or not wav_b.exists():
            continue
        flip = rng.random() < 0.5
        first, second = (key_b, key_a) if flip else (key_a, key_b)
        rows.append({
            "sentence_id": sentence["id"],
            "text": sentence["text"],
            "slot_a_key": first,
            "slot_b_key": second,
            "wav_a": (out_dir / first / f"{sentence['id']}.wav").relative_to(out_dir).as_posix(),
            "wav_b": (out_dir / second / f"{sentence['id']}.wav").relative_to(out_dir).as_posix(),
        })

    if not rows:
        raise SystemExit(f"no overlapping renders for {key_a} and {key_b}")

    csv_path = out_dir / "blind_ratings.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["sentence_id", "slot_a_key", "slot_b_key",
                        "rating_a_1to5", "rating_b_1to5", "preference", "notes"],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow({
                "sentence_id": row["sentence_id"],
                "slot_a_key": row["slot_a_key"],
                "slot_b_key": row["slot_b_key"],
            })

    cards = []
    for row in rows:
        cards.append(f"""
    <section class="card" data-sentence="{html.escape(row['sentence_id'])}">
      <p class="text">{html.escape(row['text'])}</p>
      <div class="pair">
        <div><h3>A</h3><audio controls src="{row['wav_a']}"></audio>
          <div class="scale">{_scale_buttons('rating_a')}</div></div>
        <div><h3>B</h3><audio controls src="{row['wav_b']}"></audio>
          <div class="scale">{_scale_buttons('rating_b')}</div></div>
      </div>
      <div class="scale pref">
        <span>Preference:</span>
        <button data-pick="A">A</button><button data-pick="same">same</button><button data-pick="B">B</button>
      </div>
    </section>""")

    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Phase 0 blind A/B</title>
<style>
 :root {{ color-scheme: light; }}
 body {{ font: 15px/1.5 system-ui, sans-serif; margin: 0 auto; max-width: 880px; padding: 24px; }}
 .card {{ border: 1px solid #ddd; border-radius: 10px; padding: 14px 16px; margin: 14px 0; }}
 .text {{ font-size: 17px; margin: 0 0 10px; }}
 .pair {{ display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }}
 audio {{ width: 100%; }}
 h3 {{ margin: 0 0 6px; font-size: 13px; letter-spacing: .1em; color: #666; }}
 .scale {{ display: flex; gap: 6px; align-items: center; margin-top: 8px; flex-wrap: wrap; }}
 .scale button {{ border: 1px solid #bbb; background: #fff; border-radius: 6px; padding: 4px 10px; cursor: pointer; }}
 .scale button.on {{ background: #111; color: #fff; border-color: #111; }}
 header {{ position: sticky; top: 0; background: #fff; padding: 10px 0; border-bottom: 1px solid #eee; }}
</style></head><body>
<header>
  <h1>Phase 0 — blind A/B</h1>
  <p>Order is shuffled per sentence with the run seed. Rate each clip 1–5 and pick a preference, then download the CSV.</p>
  <button id="dump">Download ratings CSV</button>
  <p id="progress"></p>
</header>
{''.join(cards)}
<script>
const rows = {rows!r};
const state = {{}};
document.querySelectorAll('.card').forEach(card => {{
  const id = card.dataset.sentence;
  state[id] = {{}};
  card.querySelectorAll('.scale:not(.pref) button').forEach(btn => {{
    const slot = btn.parentElement.dataset.slot;
    btn.onclick = () => {{
      card.querySelectorAll(`.scale[data-slot="${{slot}}"] button`).forEach(b => b.classList.remove('on'));
      btn.classList.add('on');
      state[id][slot] = btn.dataset.v;
      update();
    }};
  }});
  card.querySelectorAll('.pref button').forEach(btn => {{
    btn.onclick = () => {{
      card.querySelectorAll('.pref button').forEach(b => b.classList.remove('on'));
      btn.classList.add('on');
      state[id].preference = btn.dataset.pick;
      update();
    }};
  }});
}});
function done() {{
  return Object.values(state).filter(s => s.rating_a && s.rating_b && s.preference).length;
}}
function update() {{ document.getElementById('progress').textContent = `${{done()}} / ${{rows.length}} rated`; }}
document.getElementById('dump').onclick = () => {{
  const head = ['sentence_id','slot_a_key','slot_b_key','rating_a_1to5','rating_b_1to5','preference','notes'];
  const lines = [head.join(',')];
  for (const row of rows) {{
    const s = state[row.sentence_id] || {{}};
    lines.push([row.sentence_id, row.slot_a_key, row.slot_b_key,
      s.rating_a || '', s.rating_b || '', s.preference || '', ''].join(','));
  }}
  const blob = new Blob([lines.join('\\n')], {{type: 'text/csv'}});
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = 'blind_ratings_filled.csv';
  link.click();
}};
update();
</script>
</body></html>"""

    target = out_dir / "blind_ab.html"
    target.write_text(page, encoding="utf-8")
    print(f"wrote {target}")
    print(f"wrote {csv_path}")
    print("blind mapping stays in blind_ratings.csv; do not open it before rating")
    return target


def _scale_buttons(slot: str) -> str:
    buttons = "".join(
        f'<button data-v="{value}">{value}</button>' for value in range(1, 6)
    )
    return f'<span class="scale" data-slot="{slot}">{buttons}</span>'

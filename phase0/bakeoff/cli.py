"""Command line entry points for the Phase 0 bake-off."""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import sys
from pathlib import Path

from . import audioio, cli_io, config, renderers, report

TIMING_FIELDS = [
    "candidate", "sentence_id", "seconds", "wall_s", "rtf", "sample_rate", "note",
]


def _load_sentences() -> list[dict]:
    return cli_io.read_json(config.SENTENCES_FILE)["sentences"]


def _resolve_candidates(spec: str | None) -> list[config.Candidate]:
    if not spec:
        return list(config.CANDIDATES)
    wanted = [part.strip() for part in spec.split(",") if part.strip()]
    unknown = [key for key in wanted if key not in config.CANDIDATE_BY_KEY]
    if unknown:
        raise SystemExit(
            f"unknown candidate(s): {', '.join(unknown)}\n"
            f"available: {', '.join(config.CANDIDATE_BY_KEY)}"
        )
    return [config.CANDIDATE_BY_KEY[key] for key in wanted]


def _print_banner(candidates: list[config.Candidate]) -> None:
    print(f"device: {config.device()}   seed: {config.SEED}")
    width = max(len(c.key) for c in candidates)
    for candidate in candidates:
        print(f"  {candidate.key.ljust(width)}  {renderers.probe(candidate):<40} {candidate.label}")


def cmd_doctor(args: argparse.Namespace) -> int:
    from . import doctor

    return doctor.main()


def cmd_sentences(args: argparse.Namespace) -> int:
    payload = cli_io.read_json(config.SENTENCES_FILE)
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0
    print(f"{len(payload['sentences'])} sentences (set v{payload.get('version', '?')})")
    for sentence in payload["sentences"]:
        print(f"  {sentence['id']:<4} {sentence['category']:<18} {sentence['text']}")
    return 0


def cmd_check(args: argparse.Namespace) -> int:
    found = audioio.inspect_reference(args.reference)
    print(f"reference: {found.path}")
    print(f"  {found.seconds:.2f}s  {found.sample_rate} Hz  {found.channels} ch")
    print(f"  peak {found.peak_dbfs:.1f} dBFS  rms {found.rms_dbfs:.1f} dBFS  "
          f"silence {found.silence_fraction:.0%}")
    for warning in found.warnings:
        print(f"  {warning}")
    if not found.usable:
        print("NOT USABLE — fix the blocking issues above")
        return 1
    print("usable")
    return 0


def cmd_render(args: argparse.Namespace) -> int:
    out_dir = Path(args.out) if args.out else config.out_dir()
    candidates = _resolve_candidates(args.candidates)
    _print_banner(candidates)
    if args.dry_run:
        print("dry run: nothing rendered")
        return 0

    sentences = _load_sentences()
    if args.sentences:
        wanted = {part.strip() for part in args.sentences.split(",") if part.strip()}
        sentences = [s for s in sentences if s["id"] in wanted]
    if args.limit:
        sentences = sentences[: args.limit]

    reference_source = Path(args.reference)
    if not reference_source.exists():
        raise SystemExit(f"reference not found: {reference_source}")
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = out_dir / "manifest.json"
    previous: dict = {}
    if manifest_path.exists():
        previous = cli_io.read_json(manifest_path)
    previous_reference = previous.get("reference") or {}
    previous_sha = previous_reference.get("source_sha256") or previous_reference.get("sha256")

    reference = out_dir / "reference.wav"
    source_sha = audioio.sha256(reference_source)
    if previous_sha and previous_sha != source_sha:
        raise SystemExit(
            "this run directory was rendered with a different reference clip.\n"
            "Pick a fresh --out (for example --out phase0/out2); mixing clips "
            "would make the scores meaningless."
        )
    reference_info = audioio.prepare_reference(reference_source, reference)
    for warning in reference_info["warnings"]:
        print(f"  {warning}")
    if any(w.startswith("BLOCK") for w in reference_info["warnings"]):
        raise SystemExit("reference rejected; run 'check' for details")

    # Rendering a second sweep must not erase what earlier sweeps produced:
    # score_run() reads this list, so it is a union, never a replacement.
    previous_keys: list[str] = list(previous.get("candidates") or [])
    merged_keys = previous_keys + [c.key for c in candidates if c.key not in previous_keys]

    cli_io.write_json(manifest_path, {
        "created_at": previous.get("created_at")
        or dt.datetime.now().isoformat(timespec="seconds"),
        "updated_at": dt.datetime.now().isoformat(timespec="seconds"),
        "seed": config.SEED,
        "device": config.device(),
        "reference": reference_info,
        "reference_transcript": args.ref_text or previous.get("reference_transcript"),
        "config_b": config.CONFIG_B,
        "cfg_weight": config.CFG_WEIGHT,
        "language_id": config.TTS_LANGUAGE_ID,
        "sentence_set": str(config.SENTENCES_FILE.name),
        "sentence_set_version": cli_io.read_json(config.SENTENCES_FILE).get("version"),
        "candidates": merged_keys,
    })

    timing_path = out_dir / "timings.csv"
    fresh_timings = not timing_path.exists()
    rendered = skipped = 0

    with timing_path.open("a", newline="", encoding="utf-8") as timing_file:
        writer = csv.DictWriter(timing_file, fieldnames=TIMING_FIELDS)
        if fresh_timings:
            writer.writeheader()

        for candidate in candidates:
            folder = out_dir / candidate.key
            folder.mkdir(parents=True, exist_ok=True)
            for sentence in sentences:
                target = folder / f"{sentence['id']}.wav"
                if target.exists() and not args.force:
                    print(f"skip {candidate.key}/{sentence['id']} (exists)")
                    skipped += 1
                    continue
                label = f"{candidate.key}/{sentence['id']}"
                try:
                    print(f"render {label} ...", end="", flush=True)
                    outcome = renderers.render(
                        candidate, sentence, reference, args.ref_text, target
                    )
                except renderers.RenderSkip as error:
                    print(f" skipped: {error}")
                    skipped += 1
                    continue
                except KeyboardInterrupt:
                    print("\ninterrupted; partial files are kept")
                    return 130
                rtf = outcome.wall_s / outcome.seconds if outcome.seconds else 0.0
                writer.writerow({
                    "candidate": candidate.key,
                    "sentence_id": sentence["id"],
                    "seconds": round(outcome.seconds, 3),
                    "wall_s": round(outcome.wall_s, 3),
                    "rtf": round(rtf, 4),
                    "sample_rate": outcome.sample_rate,
                    "note": outcome.note,
                })
                timing_file.flush()
                print(f" {outcome.seconds:.2f}s audio in {outcome.wall_s:.1f}s (RTF {rtf:.2f})")
                rendered += 1

    print(f"done: {rendered} rendered, {skipped} skipped → {out_dir}")
    return 0


def cmd_score(args: argparse.Namespace) -> int:
    from . import scoring

    out_dir = Path(args.out) if args.out else config.out_dir()
    scoring.score_run(out_dir)
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    out_dir = Path(args.out) if args.out else config.out_dir()
    report.write_report(out_dir, publish=args.publish)
    return 0


def cmd_blind(args: argparse.Namespace) -> int:
    out_dir = Path(args.out) if args.out else config.out_dir()
    report.blind_ab(out_dir, args.a, args.b)
    return 0


def cmd_all(args: argparse.Namespace) -> int:
    if cmd_check(args) != 0:
        return 1
    if cmd_render(args) != 0:
        return 1
    if cmd_score(args) != 0:
        return 1
    return cmd_report(args)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="phase0",
        description="Hindi voice-cloning bake-off (quality first, on-device second).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sentences = sub.add_parser("sentences", help="show the fixed Hindi test set")
    sentences.add_argument("--json", action="store_true")
    sentences.set_defaults(func=cmd_sentences)

    doctor = sub.add_parser("doctor", help="check the installed torch/ASR/TTS stack")
    doctor.set_defaults(func=cmd_doctor)

    check = sub.add_parser("check", help="validate the reference clip")
    check.add_argument("--reference", required=True)
    check.set_defaults(func=cmd_check)

    render = sub.add_parser("render", help="synthesize every candidate")
    render.add_argument("--reference", required=True)
    render.add_argument("--ref-text", default=None,
                        help="exact reference transcript (required for IndicF5)")
    render.add_argument("--candidates", default=None,
                        help=f"comma list, default all: {','.join(config.CANDIDATE_BY_KEY)}")
    render.add_argument("--sentences", default=None, help="comma list of sentence ids")
    render.add_argument("--limit", type=int, default=None, help="only the first N sentences")
    render.add_argument("--out", default=None)
    render.add_argument("--force", action="store_true", help="re-render existing wavs")
    render.add_argument("--dry-run", action="store_true")
    render.set_defaults(func=cmd_render)

    score = sub.add_parser("score", help="ASR + speaker SIM + UTMOS + RTF")
    score.add_argument("--out", default=None)
    score.set_defaults(func=cmd_score)

    rep = sub.add_parser("report", help="aggregate results into a markdown table")
    rep.add_argument("--out", default=None)
    rep.add_argument("--publish", action="store_true",
                     help="also write into docs/phase0-hindi-bakeoff.md")
    rep.set_defaults(func=cmd_report)

    blind = sub.add_parser("blind", help="build a seeded blind A/B sheet")
    blind.add_argument("--a", required=True)
    blind.add_argument("--b", required=True)
    blind.add_argument("--out", default=None)
    blind.set_defaults(func=cmd_blind)

    all_cmd = sub.add_parser("all", help="check + render + score + report")
    all_cmd.add_argument("--reference", required=True)
    all_cmd.add_argument("--ref-text", default=None)
    all_cmd.add_argument("--candidates", default=None)
    all_cmd.add_argument("--sentences", default=None)
    all_cmd.add_argument("--limit", type=int, default=None)
    all_cmd.add_argument("--out", default=None)
    all_cmd.add_argument("--force", action="store_true")
    all_cmd.add_argument("--dry-run", action="store_true")
    all_cmd.add_argument("--publish", action="store_true")
    all_cmd.set_defaults(func=cmd_all)

    return parser


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args) or 0)


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())

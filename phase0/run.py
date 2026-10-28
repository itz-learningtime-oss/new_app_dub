#!/usr/bin/env python3
"""Entry point so the harness runs from a plain `python phase0/run.py ...`."""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

for stream in (sys.stdout, sys.stderr):
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace")

from bakeoff.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())

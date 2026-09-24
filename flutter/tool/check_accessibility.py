#!/usr/bin/env python3
"""Static accessibility checks for flutter/lib - remaining-gaps item 14.

Catches regressions of the concrete gaps fixed in the accessibility pass:
  * IconButton without a tooltip (icon-only buttons need an accessible name)
  * Image.network/.file/.asset/.memory without semanticLabel or excludeFromSemantics
  * GestureDetector not wrapped in Semantics (tap targets are otherwise unnamed)
  * error text rendered as a plain Text(_error!) instead of the live-region ErrorText
  * any textScaler / textScaleFactor override (would defeat the OS text-size setting)
Runs with only the Python standard library; exits 1 on violations.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "lib"


def calls(src: str, name: str):
    for m in re.finditer(r"\b" + re.escape(name) + r"\(", src):
        i, depth = m.end(), 1
        while depth and i < len(src):
            depth += {"(": 1, ")": -1}.get(src[i], 0)
            i += 1
        yield m.start(), src[m.start():i]


def check(root: Path = ROOT) -> list[str]:
    problems = []
    for path in sorted(root.rglob("*.dart")):
        src = path.read_text(encoding="utf-8")
        rel = path.relative_to(root.parent)

        def where(pos: int) -> str:
            return f"{rel}:{src.count(chr(10), 0, pos) + 1}"

        for pos, block in calls(src, "IconButton"):
            if "tooltip:" not in block:
                problems.append(f"{where(pos)} IconButton without tooltip")
        for kind in ("Image.network", "Image.file", "Image.asset", "Image.memory"):
            for pos, block in calls(src, kind):
                if "semanticLabel:" not in block and "excludeFromSemantics: true" not in block:
                    problems.append(f"{where(pos)} {kind} without semanticLabel/excludeFromSemantics")
        for pos, _ in calls(src, "GestureDetector"):
            if "Semantics(" not in src[max(0, pos - 300):pos]:
                problems.append(f"{where(pos)} GestureDetector not wrapped in Semantics")
        for m in re.finditer(r"\bText\(\s*_error!", src):
            problems.append(f"{where(m.start())} error shown with Text(); use ErrorText (live region)")
        for m in re.finditer(r"\btextScale(r|Factor)\s*:", src):
            problems.append(f"{where(m.start())} text scale override")
    return problems


def main() -> int:
    problems = check()
    for p in problems:
        print(f"A11Y {p}")
    files = len(list(ROOT.rglob("*.dart")))
    print(f"{files} Dart files checked, {len(problems)} accessibility violation(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

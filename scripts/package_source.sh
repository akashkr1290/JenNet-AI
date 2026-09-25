#!/usr/bin/env bash
# Audit GAP-019: build a distributable source archive that can never contain
# .env, .git/, or any other ignored file - only what Git tracks at HEAD.
set -euo pipefail
out="${1:-JanNet_AI-source.zip}"
cd "$(git rev-parse --show-toplevel)"
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "WARNING: uncommitted changes are NOT included (git archive packages HEAD only)." >&2
fi
git archive --format=zip --prefix=JanNet_AI/ -o "$out" HEAD
if unzip -l "$out" | grep -qE '(^|/)\.env$'; then
  echo "ERROR: .env found in archive - aborting" >&2; rm -f "$out"; exit 1
fi
echo "Wrote $out ($(unzip -l "$out" | tail -1 | awk '{print $2}') files, no .env/.git)"

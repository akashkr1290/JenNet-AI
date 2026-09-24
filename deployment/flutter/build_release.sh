#!/bin/bash
# JanNet AI — Phase 22: build the Flutter app pointed at the production
# API, using api_config.dart's existing --dart-define(API_BASE_URL)
# mechanism (Phase 6/17) — no Flutter source change needed for this.
#
# Usage: ./build_release.sh <api-base-url> [apk|appbundle]
# Example: ./build_release.sh https://api.jannetai.example.org/api/v1 apk
#
# NOT VERIFIED — this workspace has no Flutter SDK (a constraint present
# since Phase 6, unchanged through every phase since, including this
# one — see deployment/VERIFICATION.md). Every flag below matches
# flutter-ci.yml's (Phase 21) already-working, real `flutter build apk
# --debug` invocation, extended to `--release` and this project's real
# `--dart-define` name.

set -euo pipefail
API_BASE_URL="${1:?Usage: build_release.sh <api-base-url> [apk|appbundle]}"
BUILD_TARGET="${2:-apk}"

cd "$(dirname "${BASH_SOURCE[0]}")/../../flutter"

flutter pub get

if [ "$BUILD_TARGET" == "appbundle" ]; then
  echo "Building an Android App Bundle (.aab) — REQUIRES a real upload keystore"
  echo "(android/key.properties + a real .jks file), NEITHER of which exists"
  echo "in this repo or this sandbox — see ../README.md's 'Flutter release"
  echo "signing' section for why this is an explicit open item, not built here."
  flutter build appbundle --release --dart-define=API_BASE_URL="$API_BASE_URL"
else
  # An unsigned/debug-signed release APK still works for direct
  # sideload-based pilot distribution (SRS's own academic-pilot framing,
  # Section 30) — just not for a Play Store listing, which needs the
  # appbundle path above with a real keystore.
  flutter build apk --release --dart-define=API_BASE_URL="$API_BASE_URL"
fi

echo "Build output: flutter/build/app/outputs/"

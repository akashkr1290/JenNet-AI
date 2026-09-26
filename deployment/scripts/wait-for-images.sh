#!/bin/bash
# JanNet AI — wait until the three release images for a tag exist in GHCR.
#
# Audit fix Phase 07: deploy-aws.yml and release-image-publish.yml both start
# on the same v*.*.* tag push. The deploy used to run immediately and ask the
# host to pull images that were still being built (they take minutes), so the
# first deploy of every release failed. deploy-aws.yml now calls this first.
#
# Usage: ./wait-for-images.sh <ghcr-image-owner> <version-tag> [timeout-seconds]
# Needs `docker` logged in to ghcr.io when the packages are private.

set -euo pipefail
OWNER="${1:?Usage: wait-for-images.sh <ghcr-image-owner> <version-tag> [timeout-seconds]}"
TAG="${2:?Missing version-tag}"
TIMEOUT="${3:-2400}"
PREFIX="ghcr.io/$(echo "$OWNER" | tr '[:upper:]' '[:lower:]')"
deadline=$(( $(date +%s) + TIMEOUT ))

for image in backend ai-service frontend; do
  ref="$PREFIX/$image:$TAG"
  until docker manifest inspect "$ref" > /dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "::error::$ref is not available after ${TIMEOUT}s - did release-image-publish.yml fail?"
      exit 1
    fi
    echo "Waiting for $ref ..."
    sleep 30
  done
  echo "Found $ref"
done

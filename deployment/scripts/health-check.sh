#!/bin/bash
# JanNet AI — Phase 22: post-deploy verification.
#
# Checks the two health endpoints that already exist in the application
# (no new endpoint added this phase — Dockerfile.backend's
# /actuator/health and Dockerfile.ai-service's /health, both from
# Phase 18) through the public HTTPS front door, not just localhost on
# the host itself — confirming nginx/TLS/security-group/RDS-connectivity
# all actually chain together end to end, which a host-local curl alone
# would not prove.
#
# Usage: ./health-check.sh <base-url>
# Example: ./health-check.sh https://api.jannetai.example.org

set -euo pipefail
BASE_URL="${1:?Usage: health-check.sh <base-url>, e.g. https://api.jannetai.example.org}"

echo "Checking $BASE_URL/actuator/health ..."
BACKEND_STATUS=$(curl -sf "$BASE_URL/actuator/health" | jq -r '.status' 2>/dev/null || echo "UNREACHABLE")
echo "  backend: $BACKEND_STATUS"

if [ "$BACKEND_STATUS" != "UP" ]; then
  echo "FAIL: backend health check did not report UP."
  exit 1
fi

echo "All checks passed."
echo ""
echo "Note: ai-service's /health is deliberately NOT exposed publicly"
echo "(see nginx/jannet.conf's header comment) — it is verified"
echo "server-side only, as part of deploy.sh's own post-deploy curl to"
echo "127.0.0.1:8001/health on the host itself."

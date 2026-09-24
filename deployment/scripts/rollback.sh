#!/bin/bash
# JanNet AI — Phase 22: roll back to the previous known-good image tag.
#
# Two modes:
#   1. Explicit tag rollback (preferred — deterministic):
#        ./rollback.sh <instance-id> <ghcr-image-owner> <known-good-tag> [aws-region]
#      This is just deploy.sh pointed at an older tag — a "rollback" is
#      "deploy" with a smaller version number, nothing more exotic.
#   2. Automatic rollback to whatever was running immediately before the
#      last deploy.sh run (recorded in
#      /opt/jannet-ai/previous-*-image.txt by deploy.sh itself):
#        ./rollback.sh <instance-id> --auto [aws-region]
#
# See ../ROLLBACK.md for the full decision tree (when to roll back vs.
# fix forward) and this project's SLA-severity context for how urgently
# to act.

set -euo pipefail

INSTANCE_ID="${1:?Usage: rollback.sh <instance-id> [<ghcr-image-owner> <known-good-tag> | --auto] [aws-region]}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ "${2:-}" == "--auto" ]; then
  AWS_REGION="${3:-ap-south-1}"
  echo "Auto-rollback: reading previously-running image tags from the instance ..."

  COMMAND_ID=$(aws ssm send-command \
    --region "$AWS_REGION" \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --parameters commands="['cat /opt/jannet-ai/previous-backend-image.txt']" \
    --query 'Command.CommandId' --output text)
  aws ssm wait command-executed --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" || true
  PREVIOUS_BACKEND_IMAGE=$(aws ssm get-command-invocation --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query 'StandardOutputContent' --output text | tr -d '\n')

  if [ -z "$PREVIOUS_BACKEND_IMAGE" ]; then
    echo "No recorded previous image found (this may be the first-ever deploy — nothing to roll back to). Aborting."
    exit 1
  fi

  # PREVIOUS_BACKEND_IMAGE looks like ghcr.io/org/repo/backend:vX.Y.Z
  GHCR_IMAGE_OWNER=$(echo "$PREVIOUS_BACKEND_IMAGE" | sed -E 's#ghcr.io/([^/]+/[^/]+)/backend:.*#\1#')
  VERSION_TAG=$(echo "$PREVIOUS_BACKEND_IMAGE" | sed -E 's#.*:##')

  echo "Rolling back to $GHCR_IMAGE_OWNER at $VERSION_TAG"
  "$SCRIPT_DIR/deploy.sh" "$INSTANCE_ID" "$GHCR_IMAGE_OWNER" "$VERSION_TAG" "$AWS_REGION"
else
  GHCR_IMAGE_OWNER="${2:?Missing ghcr-image-owner}"
  VERSION_TAG="${3:?Missing known-good-tag}"
  AWS_REGION="${4:-ap-south-1}"
  echo "Explicit rollback to $GHCR_IMAGE_OWNER at $VERSION_TAG"
  "$SCRIPT_DIR/deploy.sh" "$INSTANCE_ID" "$GHCR_IMAGE_OWNER" "$VERSION_TAG" "$AWS_REGION"
fi

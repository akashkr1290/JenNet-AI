#!/bin/bash
# JanNet AI — Phase 22: deploy a new (already-published) image tag to the
# running EC2 app host.
#
# Uses AWS Systems Manager Run Command, not SSH — no private key needs
# to exist in CI at all (matches this project's "no secret values live
# in source control" convention one step further: not even an SSH key).
# The instance's own IAM role (aws/terraform/iam.tf) is what authorizes
# this, scoped by whichever IAM identity runs THIS script having
# ssm:SendCommand permission on the target instance — a separate,
# operator-side AWS credential (e.g. a CI role), never anything stored
# in this repo.
#
# Usage:
#   ./deploy.sh <instance-id> <ghcr-image-owner> <version-tag> [aws-region]
#
# Example:
#   ./deploy.sh i-0123456789abcdef0 your-org/jannet-ai v1.1.0 ap-south-1
#
# NOT VERIFIED against a real instance (no live AWS account available in
# this sandbox) — see ../VERIFICATION.md.

set -euo pipefail

INSTANCE_ID="${1:?Usage: deploy.sh <instance-id> <ghcr-image-owner> <version-tag> [aws-region]}"
GHCR_IMAGE_OWNER="${2:?Missing ghcr-image-owner}"
VERSION_TAG="${3:?Missing version-tag}"
AWS_REGION="${4:-ap-south-1}"
APP_DIR="/opt/jannet-ai/repo"

echo "Deploying $GHCR_IMAGE_OWNER images at tag $VERSION_TAG to $INSTANCE_ID ..."

COMMAND_ID=$(aws ssm send-command \
  --region "$AWS_REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --comment "JanNet AI deploy $VERSION_TAG" \
  --parameters commands="[
    'set -euo pipefail',
    'cd $APP_DIR',
    'export BACKEND_IMAGE=ghcr.io/$GHCR_IMAGE_OWNER/backend:$VERSION_TAG',
    'export AI_SERVICE_IMAGE=ghcr.io/$GHCR_IMAGE_OWNER/ai-service:$VERSION_TAG',
    'echo \"Recording previously-running tag for rollback ...\"',
    'docker ps --format \"{{.Image}}\" | grep backend | head -1 > /opt/jannet-ai/previous-backend-image.txt || true',
    'docker ps --format \"{{.Image}}\" | grep ai-service | head -1 > /opt/jannet-ai/previous-ai-service-image.txt || true',
    'docker compose -f docker/docker-compose.yml -f ../docker-compose.prod-override.yml pull',
    'docker compose -f docker/docker-compose.yml -f ../docker-compose.prod-override.yml up -d',
    'sleep 15',
    'curl -f http://127.0.0.1:8080/actuator/health',
    'curl -f http://127.0.0.1:8001/health'
  ]" \
  --query 'Command.CommandId' --output text)

echo "SSM command sent: $COMMAND_ID"
echo "Waiting for completion..."
aws ssm wait command-executed --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" || true

STATUS=$(aws ssm get-command-invocation --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query 'Status' --output text)

aws ssm get-command-invocation --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query 'StandardOutputContent' --output text

if [ "$STATUS" != "Success" ]; then
  echo "Deploy command finished with status: $STATUS — see stderr below and ../ROLLBACK.md"
  aws ssm get-command-invocation --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query 'StandardErrorContent' --output text
  exit 1
fi

echo "Deploy of $VERSION_TAG succeeded and both health checks passed."

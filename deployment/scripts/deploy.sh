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

# GHCR repository paths are lowercase (release-image-publish.yml publishes
# lowercase names even when the GitHub repository name has capitals).
IMAGE_PREFIX="ghcr.io/$(echo "$GHCR_IMAGE_OWNER" | tr '[:upper:]' '[:lower:]')"

# Audit fix Phase 07 (GAP-017 / GAP-018):
#  - compose files are addressed from the repository root
#    (deployment/docker-compose.prod-override.yml); the old
#    ../docker-compose.prod-override.yml resolved outside the clone;
#  - --env-file .env so ${AWS_REGION}/${LOG_GROUP_PREFIX} are interpolated;
#  - the checkout is moved to the release tag first, so the compose/nginx
#    configuration always matches the images being started (the bootstrap
#    clone is a shallow clone of the first release tag);
#  - the web frontend image is deployed and health-checked too.
COMPOSE="docker compose --env-file .env -f docker/docker-compose.yml -f deployment/docker-compose.prod-override.yml"

echo "Deploying $IMAGE_PREFIX images at tag $VERSION_TAG to $INSTANCE_ID ..."

COMMAND_ID=$(aws ssm send-command \
  --region "$AWS_REGION" \
  --instance-ids "$INSTANCE_ID" \
  --document-name "AWS-RunShellScript" \
  --comment "JanNet AI deploy $VERSION_TAG" \
  --parameters commands="[
    'set -euo pipefail',
    'cd $APP_DIR',
    'git fetch --depth 1 origin tag $VERSION_TAG',
    'git checkout -q $VERSION_TAG',
    'export BACKEND_IMAGE=$IMAGE_PREFIX/backend:$VERSION_TAG',
    'export AI_SERVICE_IMAGE=$IMAGE_PREFIX/ai-service:$VERSION_TAG',
    'export FRONTEND_IMAGE=$IMAGE_PREFIX/frontend:$VERSION_TAG',
    'echo \"Recording previously-running tag for rollback ...\"',
    'docker ps --format \"{{.Image}}\" | grep /backend: | head -1 > /opt/jannet-ai/previous-backend-image.txt || true',
    'docker ps --format \"{{.Image}}\" | grep /ai-service: | head -1 > /opt/jannet-ai/previous-ai-service-image.txt || true',
    'docker ps --format \"{{.Image}}\" | grep /frontend: | head -1 > /opt/jannet-ai/previous-frontend-image.txt || true',
    '$COMPOSE pull',
    '$COMPOSE up -d',
    'sleep 15',
    'curl -f http://127.0.0.1:8080/actuator/health',
    'curl -f http://127.0.0.1:8001/health',
    'curl -f http://127.0.0.1:3000/healthz'
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

echo "Deploy of $VERSION_TAG succeeded and all three health checks passed."

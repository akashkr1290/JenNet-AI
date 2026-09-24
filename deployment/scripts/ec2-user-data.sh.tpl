#!/bin/bash
# JanNet AI — Phase 22: EC2 first-boot bootstrap.
#
# Rendered by terraform/ec2.tf's templatefile() call with this
# deployment's real values (no secrets among them — see below for where
# actual secret values come from at runtime, never from this template).
# Runs exactly once, as root, via AWS's standard cloud-init user-data
# mechanism on Amazon Linux 2023 — NOT re-run on every reboot/redeploy;
# see ../scripts/deploy.sh for how a new image tag is rolled out to an
# already-running instance without a fresh EC2 launch.
#
# NOT VERIFIED end-to-end (no live AWS account/credentials available in
# this sandbox — see ../VERIFICATION.md for the full breakdown of what
# was and wasn't actually executed for Phase 22). Every command below is
# a real, current AWS CLI v2 / Docker Engine / Amazon Linux 2023 `dnf`
# command, cross-checked against each tool's own current documentation —
# not a guessed syntax — but the full boot sequence has never run
# against a real EC2 instance.

set -euo pipefail
exec > >(tee -a /var/log/jannet-ai-bootstrap.log) 2>&1
echo "=== JanNet AI bootstrap starting: $(date -u) ==="

PROJECT_NAME="${project_name}"
ENVIRONMENT="${environment}"
AWS_REGION="${aws_region}"
GHCR_IMAGE_OWNER="${ghcr_image_owner}"
APP_VERSION_TAG="${app_version_tag}"
DB_ENDPOINT="${db_endpoint}"
DB_PORT="${db_port}"
DB_NAME="${db_name}"
DB_SECRET_ARN="${db_secret_arn}"
DOMAIN_NAME="${domain_name}"
APP_DIR="/opt/jannet-ai"
SSM_PATH="/$PROJECT_NAME/$ENVIRONMENT"

# ---- 1. System packages: Docker + AWS CLI v2 (Amazon Linux 2023 ships
#         Python3 but not the CLI or Docker by default) -----------------
dnf update -y
dnf install -y docker git unzip jq

curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
unzip -q /tmp/awscliv2.zip -d /tmp
/tmp/aws/install
rm -rf /tmp/awscliv2.zip /tmp/aws

systemctl enable --now docker
usermod -aG docker ec2-user

# Docker Compose v2 plugin (docker-compose.yml already targets Compose
# v2 syntax — Phase 18's own file has no `version:` key, a v2 convention).
DOCKER_CONFIG_DIR="/usr/libexec/docker/cli-plugins"
mkdir -p "$DOCKER_CONFIG_DIR"
curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
  -o "$DOCKER_CONFIG_DIR/docker-compose"
chmod +x "$DOCKER_CONFIG_DIR/docker-compose"

# ---- 2. Fetch this repo's actual docker-compose.yml + Dockerfiles ------
# Only docker/ and the root files docker-compose.yml references are
# needed on the host — not a full application source checkout (the
# application itself runs as prebuilt GHCR images, not built on this
# host). A shallow, tag-pinned checkout keeps this reproducible and
# avoids ever building from source on a production host.
mkdir -p "$APP_DIR"
cd "$APP_DIR"
git clone --depth 1 --branch "$APP_VERSION_TAG" \
  "https://github.com/$GHCR_IMAGE_OWNER.git" repo || \
  { echo "git clone by tag failed — falling back to a manual docker-compose.yml copy is documented in deployment/README.md's Troubleshooting section"; exit 1; }

# ---- 3. Fetch secrets: SSM Parameter Store (app secrets) + Secrets
#         Manager (RDS master password) — nothing here is hardcoded ------
# See ../ssm/PARAMETERS.md for the exact list of parameter names an
# operator must have already created under $SSM_PATH/ before this script
# runs (JWT_SECRET, AI_SERVICE_API_KEY, BOOTSTRAP_SUPER_ADMIN_*,
# SMTP_*/SMS_PROVIDER_* if notifications are enabled, GEMINI_API_KEY,
# and a GHCR pull token if the published images are private).
get_param() {
  aws ssm get-parameter --region "$AWS_REGION" --with-decryption \
    --name "$SSM_PATH/$1" --query 'Parameter.Value' --output text 2>/dev/null || echo ""
}

DB_PASSWORD_JSON=$(aws secretsmanager get-secret-value --region "$AWS_REGION" \
  --secret-id "$DB_SECRET_ARN" --query 'SecretString' --output text)
DB_USERNAME=$(echo "$DB_PASSWORD_JSON" | jq -r '.username')
DB_PASSWORD=$(echo "$DB_PASSWORD_JSON" | jq -r '.password')

JWT_SECRET=$(get_param "JWT_SECRET")
AI_SERVICE_API_KEY=$(get_param "AI_SERVICE_API_KEY")
BOOTSTRAP_SUPER_ADMIN_MOBILE=$(get_param "BOOTSTRAP_SUPER_ADMIN_MOBILE")
BOOTSTRAP_SUPER_ADMIN_PASSWORD=$(get_param "BOOTSTRAP_SUPER_ADMIN_PASSWORD")
GEMINI_API_KEY=$(get_param "GEMINI_API_KEY")
SMTP_USERNAME=$(get_param "SMTP_USERNAME")
SMTP_PASSWORD=$(get_param "SMTP_PASSWORD")
SMS_PROVIDER_API_KEY=$(get_param "SMS_PROVIDER_API_KEY")
GHCR_PULL_TOKEN=$(get_param "GHCR_PULL_TOKEN")

# ---- 4. Write .env — same variable names as .env.example, so this
#         production file follows the exact same contract application.yml
#         and app/config.py already read from; SPRING_PROFILES_ACTIVE and
#         AI_SERVICE_ENV flipped to their production values, per each
#         file's own documented "set this for a real deployment" note. ---
cat > "$APP_DIR/repo/.env" <<ENVEOF
MYSQL_ROOT_PASSWORD=UNUSED_MANAGED_RDS_NO_ROOT_ACCESS
DB_NAME=$DB_NAME
DB_USERNAME=$DB_USERNAME
DB_PASSWORD=$DB_PASSWORD
DB_HOST=$DB_ENDPOINT
DB_PORT=$DB_PORT

SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
APP_NAME=jannet-ai

JWT_SECRET=$JWT_SECRET
JWT_ACCESS_TOKEN_EXPIRY_MINUTES=30
JWT_REFRESH_TOKEN_EXPIRY_DAYS=7

BOOTSTRAP_SUPER_ADMIN_MOBILE=$BOOTSTRAP_SUPER_ADMIN_MOBILE
BOOTSTRAP_SUPER_ADMIN_PASSWORD=$BOOTSTRAP_SUPER_ADMIN_PASSWORD
BOOTSTRAP_SUPER_ADMIN_NAME=System Administrator

LOCAL_STORAGE_PATH=/app/storage

AI_SERVICE_BASE_URL=http://ai-service:8001
AI_SERVICE_API_KEY=$AI_SERVICE_API_KEY
AI_SERVICE_ENABLED=true
AI_SERVICE_CONNECT_TIMEOUT_MS=5000
AI_SERVICE_READ_TIMEOUT_MS=15000

AI_SERVICE_ENV=production
AI_SERVICE_HOST=0.0.0.0
AI_SERVICE_PORT=8001
GEMINI_API_KEY=$GEMINI_API_KEY

SMTP_USERNAME=$SMTP_USERNAME
SMTP_PASSWORD=$SMTP_PASSWORD
SMS_PROVIDER_API_KEY=$SMS_PROVIDER_API_KEY

# Gap-backlog strict recheck (Sep 2026): used by docker-compose.prod-override.yml's
# awslogs logging driver (Patch 19/20) - log groups are created by
# deployment/aws/terraform/app_monitoring.tf with the same names.
AWS_REGION=$AWS_REGION
LOG_GROUP_PREFIX=/$PROJECT_NAME/$ENVIRONMENT
# Patch 50: nginx overwrites X-Real-IP with its own $remote_addr, so the
# backend's per-IP auth rate limit can trust it (never spoofable via nginx).
RATE_LIMIT_CLIENT_IP_HEADER=X-Real-IP
ENVEOF
chmod 600 "$APP_DIR/repo/.env"

# ---- 5. Pull the Phase-21-published, version-tagged images (never built
#         on this host) and bring the stack up -----------------------
if [ -n "$GHCR_PULL_TOKEN" ]; then
  echo "$GHCR_PULL_TOKEN" | docker login ghcr.io -u "$GHCR_IMAGE_OWNER" --password-stdin
fi

cd "$APP_DIR/repo"
export BACKEND_IMAGE="ghcr.io/$GHCR_IMAGE_OWNER/backend:$APP_VERSION_TAG"
export AI_SERVICE_IMAGE="ghcr.io/$GHCR_IMAGE_OWNER/ai-service:$APP_VERSION_TAG"

# docker-compose.yml (Phase 18) builds images locally by default (no
# `image:` key) — this deployment overrides that with a small,
# deployment-only compose override (checked into deployment/, not
# docker/, per this phase's own boundary) that swaps in the two
# published GHCR image references instead. See
# deployment/docker-compose.prod-override.yml's own header for why this
# lives as an override rather than editing docker/docker-compose.yml
# itself (that file remains Phase 18's own, unmodified).
docker compose -f docker/docker-compose.yml \
  -f ../docker-compose.prod-override.yml up -d

# ---- 6. nginx reverse proxy + TLS ---------------------------------------
dnf install -y nginx
cp "$APP_DIR/repo/../nginx/jannet.conf" /etc/nginx/conf.d/jannet.conf
sed -i "s/__DOMAIN_NAME__/$DOMAIN_NAME/g" /etc/nginx/conf.d/jannet.conf
systemctl enable --now nginx

if [ -n "$DOMAIN_NAME" ]; then
  dnf install -y python3-pip
  pip3 install certbot certbot-nginx
  # --non-interactive/--agree-tos: this is a one-shot boot script with no
  # human present to answer prompts; an operator emails is passed via the
  # ACME_EMAIL SSM parameter, not hardcoded here.
  ACME_EMAIL=$(get_param "ACME_EMAIL")
  certbot --nginx -d "$DOMAIN_NAME" --non-interactive --agree-tos \
    -m "${ACME_EMAIL:-admin@$DOMAIN_NAME}" || \
    echo "certbot failed — see deployment/nginx/README.md's manual TLS setup fallback; nginx continues serving plain HTTP on 80 until resolved"
else
  echo "No domain_name configured — nginx serves plain HTTP only. See deployment/README.md's 'Domain/HTTPS' section."
fi

echo "=== JanNet AI bootstrap complete: $(date -u) ==="

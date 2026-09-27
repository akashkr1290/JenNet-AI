#!/bin/bash
# JanNet AI — AWS Free-Plan pilot: EC2 first-boot bootstrap.
#
# Sibling to ec2-user-data.sh.tpl (the full RDS/S3/CloudWatch deploy).
# Differences: MySQL is self-hosted in Docker (no RDS/Secrets Manager),
# storage is local-disk (no S3), and there is no domain/certbot step —
# nginx serves plain HTTP for the API only; the Flutter Web frontend is
# served separately from Cloudflare Pages, not from this host.
#
# NOT VERIFIED end-to-end — same caveat as the original script.

set -euo pipefail
exec > >(tee -a /var/log/jannet-ai-bootstrap.log) 2>&1
echo "=== JanNet AI pilot bootstrap starting: $(date -u) ==="

PROJECT_NAME="${project_name}"
ENVIRONMENT="${environment}"
AWS_REGION="${aws_region}"
GHCR_IMAGE_OWNER="${ghcr_image_owner}"
APP_VERSION_TAG="${app_version_tag}"
APP_DIR="/opt/jannet-ai"
SSM_PATH="/$PROJECT_NAME/$ENVIRONMENT"

# ---- 1. System packages: Docker + AWS CLI v2 ----------------------------
dnf update -y
dnf install -y docker git unzip jq

if ! command -v aws >/dev/null 2>&1; then
  curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o /tmp/awscliv2.zip
  unzip -q /tmp/awscliv2.zip -d /tmp
  /tmp/aws/install
  rm -rf /tmp/awscliv2.zip /tmp/aws
else
  echo "AWS CLI already installed - skipping (idempotent re-run)"
fi

systemctl enable --now docker
usermod -aG docker ec2-user

DOCKER_CONFIG_DIR="/usr/libexec/docker/cli-plugins"
mkdir -p "$DOCKER_CONFIG_DIR"
curl -fsSL "https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64" \
  -o "$DOCKER_CONFIG_DIR/docker-compose"
chmod +x "$DOCKER_CONFIG_DIR/docker-compose"

# ---- 1b. Swap: t3.small has ~2 GB RAM; MySQL + JVM + PyTorch need headroom.
# Without it the host froze (SSH unresponsive) during a backend crash loop.
# 2 GB file on the existing root EBS volume - no extra AWS resource/cost.
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# ---- 2. Fetch this repo's docker-compose.yml + Dockerfiles --------------
mkdir -p "$APP_DIR"
cd "$APP_DIR"
# Idempotent: a previous run may have left a partial/wrong-tag clone here.
rm -rf repo
git clone --depth 1 --branch "$APP_VERSION_TAG" \
  "https://github.com/$GHCR_IMAGE_OWNER.git" repo || \
  { echo "git clone by tag failed"; exit 1; }

# ---- 3. Fetch secrets from SSM Parameter Store only (no Secrets Manager,
#         no RDS) — see ../ssm/PARAMETERS.md for the pilot's parameter list,
#         which now also includes MYSQL_ROOT_PASSWORD/DB_PASSWORD since
#         there is no RDS-managed secret to read those from instead.
get_param() {
  aws ssm get-parameter --region "$AWS_REGION" --with-decryption \
    --name "$SSM_PATH/$1" --query 'Parameter.Value' --output text 2>/dev/null || echo ""
}

MYSQL_ROOT_PASSWORD=$(get_param "MYSQL_ROOT_PASSWORD")
DB_PASSWORD=$(get_param "DB_PASSWORD")
JWT_SECRET=$(get_param "JWT_SECRET")
AI_SERVICE_API_KEY=$(get_param "AI_SERVICE_API_KEY")
BOOTSTRAP_SUPER_ADMIN_MOBILE=$(get_param "BOOTSTRAP_SUPER_ADMIN_MOBILE")
BOOTSTRAP_SUPER_ADMIN_PASSWORD=$(get_param "BOOTSTRAP_SUPER_ADMIN_PASSWORD")
GEMINI_API_KEY=$(get_param "GEMINI_API_KEY")
SMTP_USERNAME=$(get_param "SMTP_USERNAME")
SMTP_PASSWORD=$(get_param "SMTP_PASSWORD")
SMS_PROVIDER_API_KEY=$(get_param "SMS_PROVIDER_API_KEY")
GHCR_PULL_TOKEN=$(get_param "GHCR_PULL_TOKEN")

# ---- 4. Write .env -------------------------------------------------------
ENV_FILE="$APP_DIR/repo/.env"
: > "$ENV_FILE"
chmod 600 "$ENV_FILE"
put() {
  if [ -n "$2" ]; then printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"; fi
}
put_param() {
  local value
  value=$(get_param "$1")
  put "$1" "$${value:-$${2:-}}"
}

put MYSQL_ROOT_PASSWORD "$MYSQL_ROOT_PASSWORD"
put DB_NAME "jannet_ai"
put DB_USERNAME "jannet_user"
put DB_PASSWORD "$DB_PASSWORD"
# Self-hosted MySQL container on the same Docker network — service name,
# not an RDS endpoint.
put DB_HOST "mysql"
put DB_PORT 3306
# application-prod.yml trusts only the AWS RDS CA (verifyServerCertificate=true
# against /app/certs/rds-truststore.jks). The pilot's MySQL is a container on
# this host's private Docker network (3306 bound to 127.0.0.1, not in the
# security group) with a self-signed cert no public CA can sign. Override the
# URL via Spring's env binding: TLS stays required, only chain validation is
# off. The full RDS deployment is unaffected.
put SPRING_DATASOURCE_URL "jdbc:mysql://mysql:3306/jannet_ai?useSSL=true&requireSSL=true&verifyServerCertificate=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"

put SERVER_PORT 8080
put SPRING_PROFILES_ACTIVE prod
put APP_NAME jannet-ai
put LOG_FORMAT json

put JWT_SECRET "$JWT_SECRET"
put JWT_ACCESS_TOKEN_EXPIRY_MINUTES 30
put JWT_REFRESH_TOKEN_EXPIRY_DAYS 7

put BOOTSTRAP_SUPER_ADMIN_MOBILE "$BOOTSTRAP_SUPER_ADMIN_MOBILE"
put BOOTSTRAP_SUPER_ADMIN_PASSWORD "$BOOTSTRAP_SUPER_ADMIN_PASSWORD"
put BOOTSTRAP_SUPER_ADMIN_NAME "System Administrator"
put_param BOOTSTRAP_SUPER_ADMIN_EMAIL

# No S3 in the pilot — complaint photos stay on the local Docker volume
# (backend_storage in docker/docker-compose.yml), exactly as in local dev.
put STORAGE_PROVIDER local
put LOCAL_STORAGE_PATH /app/storage
put_param LOCAL_STORAGE_SIGNING_SECRET

put_param CORS_ALLOWED_ORIGINS

put AI_SERVICE_BASE_URL http://ai-service:8001
put AI_SERVICE_API_KEY "$AI_SERVICE_API_KEY"
put AI_SERVICE_ENABLED true
put AI_SERVICE_CONNECT_TIMEOUT_MS 5000
put AI_SERVICE_READ_TIMEOUT_MS 15000

put AI_SERVICE_ENV production
put AI_SERVICE_HOST 0.0.0.0
put AI_SERVICE_PORT 8001
put REQUIRE_MODEL true
put GEMINI_API_KEY "$GEMINI_API_KEY"
put_param GEMINI_MODEL_NAME

put_param NOTIFICATION_EMAIL_ENABLED false
put_param NOTIFICATION_SMS_ENABLED false
put_param NOTIFICATION_PUSH_ENABLED false
# Pilot: e-mail the admin MFA code while SMS is off (AuthService.mfaEmailFallback).
put_param OTP_MFA_EMAIL_FALLBACK false
put_param NOTIFICATION_EMAIL_FROM
put_param SMTP_HOST
put_param SMTP_PORT
put SMTP_USERNAME "$SMTP_USERNAME"
put SMTP_PASSWORD "$SMTP_PASSWORD"
put MANAGEMENT_HEALTH_MAIL_ENABLED false
put SMS_PROVIDER_API_KEY "$SMS_PROVIDER_API_KEY"
for key in SMS_PROVIDER SMS_PROVIDER_URL SMS_PROVIDER_REQUEST_FORMAT SMS_PROVIDER_AUTH_SCHEME \
    SMS_PROVIDER_AUTH_HEADER SMS_PROVIDER_AUTH_QUERY_PARAM SMS_PROVIDER_BASIC_USERNAME SMS_PROVIDER_NUMBER_FORMAT \
    SMS_PROVIDER_TO_FIELD SMS_PROVIDER_MESSAGE_FIELD SMS_PROVIDER_SENDER_FIELD SMS_SENDER_ID \
    SMS_DLT_ENTITY_ID_FIELD SMS_DLT_ENTITY_ID SMS_DLT_TEMPLATE_ID_FIELD SMS_OTP_DLT_TEMPLATE_ID \
    SMS_NOTIFICATION_DLT_TEMPLATE_ID SMS_PROVIDER_SUCCESS_PATTERN OTP_SMS_TEMPLATE; do
  put_param "$key"
done

for key in GEO_MIN_LAT GEO_MAX_LAT GEO_MIN_LNG GEO_MAX_LNG \
    COMPLAINT_PROFANITY_WORDS COMPLAINT_PROFANITY_ACTION REPORTS_MIN_COMPLAINTS; do
  put_param "$key"
done

# ---- 5. Pull images and bring the stack up (mysql included, self-hosted) -
if [ -n "$GHCR_PULL_TOKEN" ]; then
  echo "$GHCR_PULL_TOKEN" | docker login ghcr.io -u "$GHCR_IMAGE_OWNER" --password-stdin
fi

cd "$APP_DIR/repo"
IMAGE_PREFIX="ghcr.io/$(echo "$GHCR_IMAGE_OWNER" | tr '[:upper:]' '[:lower:]')"
export BACKEND_IMAGE="$IMAGE_PREFIX/backend:$APP_VERSION_TAG"
export AI_SERVICE_IMAGE="$IMAGE_PREFIX/ai-service:$APP_VERSION_TAG"

docker compose --env-file .env -f docker/docker-compose.yml \
  -f deployment/docker-compose.pilot-override.yml up -d

# ---- 6. nginx: plain HTTP, API only (frontend is on Cloudflare Pages) ---
dnf install -y nginx
sed "s/__DOMAIN_NAME__/_/g" "$APP_DIR/repo/deployment/nginx/jannet-http.conf" \
  > /etc/nginx/conf.d/jannet.conf
nginx -t
systemctl enable --now nginx

echo "No domain configured (pilot policy: never buy a domain) — nginx serves plain HTTP only. A Cloudflare Worker reverse-proxy is the planned path to HTTPS without owning a domain; see deployment/aws/README.md."
echo "=== JanNet AI pilot bootstrap complete: $(date -u) ==="

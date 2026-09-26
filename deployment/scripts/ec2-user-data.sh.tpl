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
MEDIA_BUCKET="${media_bucket}"
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
#         file's own documented "set this for a real deployment" note.
#
# Audit fix Phase 07 (GAP-018): the previous file omitted the notification
# switches, SMTP host/port, SMS provider settings, CORS origins, storage
# provider, image-signing secret, Gemini model and the mail health switch.
# Every production-relevant setting is now written. Optional settings come
# from SSM parameters under $SSM_PATH (SecureString for secrets, String for
# plain settings - see ../ssm/PARAMETERS.md) and a line is written only when
# a value exists, so an unset parameter keeps the application default
# instead of overriding it with an empty string. The backend refuses to
# start under the prod profile when a required value is missing or unsafe
# (ProductionConfigurationValidator), instead of running half-configured.
ENV_FILE="$APP_DIR/repo/.env"
: > "$ENV_FILE"
chmod 600 "$ENV_FILE"
put() {
  # put KEY VALUE - append KEY=VALUE only when VALUE is not empty
  if [ -n "$2" ]; then printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"; fi
}
put_param() {
  # put_param KEY [DEFAULT] - value from SSM $SSM_PATH/KEY, else DEFAULT, else nothing
  local value
  value=$(get_param "$1")
  put "$1" "$${value:-$${2:-}}"
}

put MYSQL_ROOT_PASSWORD "UNUSED_MANAGED_RDS_NO_ROOT_ACCESS"
put DB_NAME "$DB_NAME"
put DB_USERNAME "$DB_USERNAME"
put DB_PASSWORD "$DB_PASSWORD"
put DB_HOST "$DB_ENDPOINT"
put DB_PORT "$DB_PORT"

put SERVER_PORT 8080
put SPRING_PROFILES_ACTIVE prod
put APP_NAME jannet-ai
# Audit GAP-041: structured JSON logs (backend logback-spring.xml, ai-service
# logging_config.py) for CloudWatch.
put LOG_FORMAT json

put JWT_SECRET "$JWT_SECRET"
put JWT_ACCESS_TOKEN_EXPIRY_MINUTES 30
put JWT_REFRESH_TOKEN_EXPIRY_DAYS 7

put BOOTSTRAP_SUPER_ADMIN_MOBILE "$BOOTSTRAP_SUPER_ADMIN_MOBILE"
put BOOTSTRAP_SUPER_ADMIN_PASSWORD "$BOOTSTRAP_SUPER_ADMIN_PASSWORD"
put BOOTSTRAP_SUPER_ADMIN_NAME "System Administrator"

# Complaint photos go to the S3 bucket Terraform created (s3.tf; the instance
# role already has access - iam.tf). LOCAL_STORAGE_PATH stays for provider=local.
put STORAGE_PROVIDER s3
put STORAGE_S3_BUCKET "$MEDIA_BUCKET"
put STORAGE_S3_REGION "$AWS_REGION"
put LOCAL_STORAGE_PATH /app/storage
put_param LOCAL_STORAGE_SIGNING_SECRET

# Browser origin of the web app (same origin as the API behind nginx).
if [ -n "$DOMAIN_NAME" ]; then
  put CORS_ALLOWED_ORIGINS "https://$DOMAIN_NAME"
else
  put_param CORS_ALLOWED_ORIGINS
fi
put RATE_LIMIT_CLIENT_IP_HEADER X-Real-IP

put AI_SERVICE_BASE_URL http://ai-service:8001
put AI_SERVICE_API_KEY "$AI_SERVICE_API_KEY"
put AI_SERVICE_ENABLED true
put AI_SERVICE_CONNECT_TIMEOUT_MS 5000
put AI_SERVICE_READ_TIMEOUT_MS 15000

put AI_SERVICE_ENV production
put AI_SERVICE_HOST 0.0.0.0
put AI_SERVICE_PORT 8001
# Audit GAP-060: refuse to start without the verified model.
put REQUIRE_MODEL true
put GEMINI_API_KEY "$GEMINI_API_KEY"
put_param GEMINI_MODEL_NAME

# Notifications (SRS 15.13). Switches default to false; set the SSM String
# parameters to "true" once the provider accounts exist
# (docs/SMS_PROVIDER_CONFIGURATION.md).
put_param NOTIFICATION_EMAIL_ENABLED false
put_param NOTIFICATION_SMS_ENABLED false
put_param NOTIFICATION_PUSH_ENABLED false
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

# Municipality-specific settings (operator data - see
# docs/SRS_PHASE06_DECISIONS.md); unset keeps the documented defaults.
for key in GEO_MIN_LAT GEO_MAX_LAT GEO_MIN_LNG GEO_MAX_LNG \
    COMPLAINT_PROFANITY_WORDS COMPLAINT_PROFANITY_ACTION REPORTS_MIN_COMPLAINTS; do
  put_param "$key"
done

# Gap-backlog strict recheck (Sep 2026): used by docker-compose.prod-override.yml's
# awslogs logging driver (Patch 19/20) - log groups are created by
# deployment/aws/terraform/app_monitoring.tf with the same names.
put AWS_REGION "$AWS_REGION"
put LOG_GROUP_PREFIX "/$PROJECT_NAME/$ENVIRONMENT"

# ---- 5. Pull the Phase-21-published, version-tagged images (never built
#         on this host) and bring the stack up -----------------------
if [ -n "$GHCR_PULL_TOKEN" ]; then
  echo "$GHCR_PULL_TOKEN" | docker login ghcr.io -u "$GHCR_IMAGE_OWNER" --password-stdin
fi

cd "$APP_DIR/repo"
# GHCR repository paths are lowercase (release-image-publish.yml publishes
# lowercase names).
IMAGE_PREFIX="ghcr.io/$(echo "$GHCR_IMAGE_OWNER" | tr '[:upper:]' '[:lower:]')"
export BACKEND_IMAGE="$IMAGE_PREFIX/backend:$APP_VERSION_TAG"
export AI_SERVICE_IMAGE="$IMAGE_PREFIX/ai-service:$APP_VERSION_TAG"
export FRONTEND_IMAGE="$IMAGE_PREFIX/frontend:$APP_VERSION_TAG"

# docker-compose.yml (Phase 18) builds images locally by default (no
# `image:` key) — the production override (deployment/docker-compose.prod-override.yml)
# swaps in the published GHCR images, drops the local mysql container and
# publishes the containers on 127.0.0.1 only.
# Audit fix Phase 07: paths are relative to the repository root (the old
# ../docker-compose.prod-override.yml pointed outside the clone), and
# --env-file .env is required for $${AWS_REGION}/$${LOG_GROUP_PREFIX}.
docker compose --env-file .env -f docker/docker-compose.yml \
  -f deployment/docker-compose.prod-override.yml up -d

# ---- 6. nginx reverse proxy + TLS ---------------------------------------
# Audit fix Phase 07 (GAP-017): nginx serves the web app at / and the API at
# /api/v1/. It starts with the plain-HTTP config (jannet-http.conf), because
# jannet.conf's 443 block needs certificate files that only exist after
# certbot; with the old order nginx failed to start and `set -e` stopped
# the bootstrap before certbot ran.
dnf install -y nginx
mkdir -p /var/www/certbot
sed "s/__DOMAIN_NAME__/$${DOMAIN_NAME:-_}/g" "$APP_DIR/repo/deployment/nginx/jannet-http.conf" \
  > /etc/nginx/conf.d/jannet.conf
nginx -t
systemctl enable --now nginx

if [ -n "$DOMAIN_NAME" ]; then
  dnf install -y python3-pip
  pip3 install certbot
  # --non-interactive/--agree-tos: this is a one-shot boot script with no
  # human present to answer prompts; the operator email comes from the
  # ACME_EMAIL SSM parameter, not hardcoded here.
  ACME_EMAIL=$(get_param "ACME_EMAIL")
  if certbot certonly --webroot -w /var/www/certbot -d "$DOMAIN_NAME" \
      --non-interactive --agree-tos -m "$${ACME_EMAIL:-admin@$DOMAIN_NAME}" \
      --deploy-hook "systemctl reload nginx"; then
    sed "s/__DOMAIN_NAME__/$DOMAIN_NAME/g" "$APP_DIR/repo/deployment/nginx/jannet.conf" \
      > /etc/nginx/conf.d/jannet.conf
    nginx -t && systemctl reload nginx
    # pip-installed certbot brings no renewal timer: add one (twice daily,
    # certbot only renews certificates that are close to expiry).
    cat > /etc/systemd/system/certbot-renew.service <<'UNITEOF'
[Unit]
Description=Renew Let's Encrypt certificates (JanNet AI)
[Service]
Type=oneshot
ExecStart=/bin/sh -c "certbot renew --quiet --deploy-hook 'systemctl reload nginx'"
UNITEOF
    cat > /etc/systemd/system/certbot-renew.timer <<'UNITEOF'
[Unit]
Description=Twice-daily certbot renewal check (JanNet AI)
[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=1h
Persistent=true
[Install]
WantedBy=timers.target
UNITEOF
    systemctl daemon-reload
    systemctl enable --now certbot-renew.timer
  else
    echo "certbot failed — nginx keeps serving plain HTTP (jannet-http.conf) until resolved; see deployment/nginx/README.md"
  fi
else
  echo "No domain_name configured — nginx serves plain HTTP only (degraded mode). See deployment/nginx/README.md."
fi

echo "=== JanNet AI bootstrap complete: $(date -u) ==="

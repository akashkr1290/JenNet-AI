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
# nginx sets X-Real-IP (real client IP via the Worker) - the only header the backend trusts.
put RATE_LIMIT_CLIENT_IP_HEADER X-Real-IP

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

# ---- 6. nginx + TLS: API only (the web app is on Cloudflare Pages) -------
# Browsers reach this host only through the Cloudflare Worker (HTTPS). The
# Worker -> EC2 hop is HTTPS too: nginx serves the API on 443 with a publicly
# trusted certificate for <public-ip>.sslip.io (Workers only accept trusted
# certificates, and the pilot has no domain). Port 80 only answers ACME
# HTTP-01 challenges and redirects everything else to HTTPS with 308 (keeps
# the method), so the API is never served in plain text.
# Certificate: Let's Encrypt first. sslip.io shares ONE Let's Encrypt quota
# for all its users (it was exhausted in Feb 2026), so if Let's Encrypt
# refuses, ZeroSSL (free ACME CA) is used - that needs ACME_EMAIL in SSM.
# The Worker sends the caller's real IP in X-JanNet-Client-IP together with
# the shared WORKER_PROXY_KEY; nginx passes that IP on as X-Real-IP (the only
# header the backend trusts - ClientIpResolver) ONLY when the key matches.
dnf install -y nginx python3.11 python3.11-pip openssl
mkdir -p /var/www/certbot
IMDS_TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 120")
PUBLIC_IP=$(curl -s -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4)
echo "$PUBLIC_IP" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' || { echo "no public IPv4 in instance metadata"; exit 1; }
ORIGIN_HOST="$${PUBLIC_IP//./-}.sslip.io"
CERT_DIR=/etc/letsencrypt/live/jannet-pilot
WORKER_PROXY_KEY=$(get_param "WORKER_PROXY_KEY")
[ -n "$WORKER_PROXY_KEY" ] || WORKER_PROXY_KEY="not-configured-$(openssl rand -hex 16)"
NGINX_CONF=/etc/nginx/conf.d/jannet.conf
[ -f "$NGINX_CONF" ] && cp -p "$NGINX_CONF" /root/jannet.conf.previous

API_LOCATIONS=$(cat <<'LOC'
    client_max_body_size 11M;

    location /actuator/health {
        proxy_pass http://127.0.0.1:8080/actuator/health;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8080/api/v1/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $jannet_client_ip;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Request-Id $request_id;
        proxy_read_timeout 30s;
        proxy_connect_timeout 5s;
    }

    # No web app on this host - it is served from Cloudflare Pages.
    location / {
        return 404;
    }
LOC
)

# write_nginx 0 = port 80 only (first boot, before a certificate exists)
# write_nginx 1 = HTTPS on 443, port 80 = ACME + 308 redirect
write_nginx() {
  umask 077
  {
    cat <<NGINX
# The 64-character proxy key does not fit nginx's default 64-byte map bucket.
map_hash_bucket_size 128;
map \$http_x_jannet_proxy_key \$jannet_via_worker {
    default 0;
    "$WORKER_PROXY_KEY" 1;
}
map \$jannet_via_worker \$jannet_client_ip {
    default \$remote_addr;
    1       \$http_x_jannet_client_ip;
}
NGINX
    if [ "$1" = 1 ]; then
      cat <<NGINX
server {
    listen 80;
    server_name _;
    access_log /var/log/nginx/jannet-http.log;
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
    location / {
        return 308 https://\$host\$request_uri;
    }
}
server {
    listen 443 ssl;
    server_name _;
    access_log /var/log/nginx/jannet-https.log;
    ssl_certificate     $CERT_DIR/fullchain.pem;
    ssl_certificate_key $CERT_DIR/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:jannet_tls:10m;
$API_LOCATIONS
}
NGINX
    else
      cat <<NGINX
server {
    listen 80;
    server_name _;
    access_log /var/log/nginx/jannet-http.log;
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
$API_LOCATIONS
}
NGINX
    fi
  } > "$NGINX_CONF"
  umask 022
}

apply_nginx() {
  if ! nginx -t; then
    # Never leave the site down: put the previous config back and stop here.
    echo "nginx rejected the new config - restoring the previous one"
    [ -f /root/jannet.conf.previous ] && cp -p /root/jannet.conf.previous "$NGINX_CONF"
    nginx -t && systemctl restart nginx
    return 1
  fi
  systemctl enable nginx
  # restart, not "enable --now": a re-run must apply a changed config
  systemctl restart nginx
}

cert_matches_host() {
  [ -f "$CERT_DIR/fullchain.pem" ] && openssl x509 -in "$CERT_DIR/fullchain.pem" -noout -text | grep -q "DNS:$ORIGIN_HOST"
}

# First boot / new IP: serve ACME on port 80 before asking for a certificate.
if ! cert_matches_host; then
  write_nginx 0
  apply_nginx || exit 1
fi

if [ ! -x /opt/certbot/bin/certbot ]; then
  python3.11 -m venv /opt/certbot
  /opt/certbot/bin/pip install --quiet --upgrade pip certbot
fi
CERTBOT_ARGS=(certonly --webroot -w /var/www/certbot -d "$ORIGIN_HOST" --cert-name jannet-pilot
              --keep-until-expiring --non-interactive --agree-tos --deploy-hook "systemctl reload nginx")
ACME_EMAIL=$(get_param "ACME_EMAIL")
if [ -n "$ACME_EMAIL" ]; then EMAIL_ARGS=(-m "$ACME_EMAIL"); else EMAIL_ARGS=(--register-unsafely-without-email); fi
if ! /opt/certbot/bin/certbot "$${CERTBOT_ARGS[@]}" "$${EMAIL_ARGS[@]}"; then
  echo "Let's Encrypt did not issue a certificate for $ORIGIN_HOST (often the shared sslip.io quota) - trying ZeroSSL"
  if [ -n "$ACME_EMAIL" ]; then
    EAB=$(curl -s --max-time 30 --data-urlencode "email=$ACME_EMAIL" https://api.zerossl.com/acme/eab-credentials-email || true)
    EAB_KID=$(echo "$EAB" | jq -r '.eab_kid // empty' 2>/dev/null || true)
    EAB_HMAC=$(echo "$EAB" | jq -r '.eab_hmac_key // empty' 2>/dev/null || true)
    if [ -n "$EAB_KID" ] && [ -n "$EAB_HMAC" ]; then
      /opt/certbot/bin/certbot "$${CERTBOT_ARGS[@]}" -m "$ACME_EMAIL" \
        --server https://acme.zerossl.com/v2/DV90 --eab-kid "$EAB_KID" --eab-hmac-key "$EAB_HMAC" || true
    else
      echo "ZeroSSL did not return account credentials"
    fi
    unset EAB EAB_KID EAB_HMAC
  else
    echo "ACME_EMAIL is not set in SSM - ZeroSSL fallback skipped"
  fi
fi
cert_matches_host || { echo "no TLS certificate for $ORIGIN_HOST - nginx keeps serving plain HTTP on port 80; see deployment/aws/PILOT_README.md"; exit 1; }

write_nginx 1
apply_nginx || exit 1
unset WORKER_PROXY_KEY

# certbot from pip has no renewal timer: run "certbot renew" twice a day
# (it only renews certificates that are close to expiry).
cat > /etc/systemd/system/certbot-renew.service <<'UNIT'
[Unit]
Description=Renew the JanNet AI pilot TLS certificate
[Service]
Type=oneshot
ExecStart=/opt/certbot/bin/certbot renew --quiet --deploy-hook "systemctl reload nginx"
UNIT
cat > /etc/systemd/system/certbot-renew.timer <<'UNIT'
[Unit]
Description=Twice-daily TLS certificate renewal check (JanNet AI pilot)
[Timer]
OnCalendar=*-*-* 03,15:17:00
RandomizedDelaySec=1h
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now certbot-renew.timer

echo "Pilot: API served at https://$ORIGIN_HOST (Cloudflare Worker origin). See deployment/aws/PILOT_README.md."
echo "=== JanNet AI pilot bootstrap complete: $(date -u) ==="

#!/usr/bin/env bash
# JanNet AI - AWS Free-Plan pilot operations. Run from your own machine.
#
#   deployment/scripts/pilot.sh status          health of every layer (EC2, containers, Worker, Pages)
#   deployment/scripts/pilot.sh redeploy [TAG]  re-run the bootstrap on the EC2 host (default: tag now running)
#   deployment/scripts/pilot.sh sync-ip         after an EC2 stop/start: point the Worker at the new public IP
#   deployment/scripts/pilot.sh deploy-web      rebuild the Flutter web app and publish it to Cloudflare Pages
#   deployment/scripts/pilot.sh set-proxy-key   (re)generate the Worker <-> nginx shared key, then redeploy
#   deployment/scripts/pilot.sh backup          mysqldump of the pilot database to ~/jannet-ai-backups
#   deployment/scripts/pilot.sh render [TAG]    print the rendered bootstrap script (debugging)
#
# Needs: aws CLI (credentials for account 710404047578), the pilot SSH key, docker,
# npx (Cloudflare wrangler, logged in), curl, python3. Never prints secret values.
# Runbook: deployment/aws/PILOT_README.md
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
REGION="${AWS_REGION:-ap-south-1}"
INSTANCE_ID="${PILOT_INSTANCE_ID:-i-06a69c838bc361f15}"
SSH_KEY="${PILOT_SSH_KEY:-$HOME/.ssh/jannet-ai/jannet-ai-pilot.pem}"
GHCR_OWNER="akashkr1290/jennet-ai"
SSM_PATH="/jannet-ai/pilot-free"
WORKER_DIR="$REPO/deployment/cloudflare/worker"
WORKER_URL="${PILOT_WORKER_URL:-https://jannet-api.jennetai129012.workers.dev}"
WEB_DIST="$REPO/deployment/cloudflare/web-dist"
PAGES_PROJECT="jannet-ai"
PAGES_URL="https://jannet-ai.pages.dev"
TEMPLATE="$REPO/deployment/scripts/ec2-user-data-pilot.sh.tpl"

die() { echo "ERROR: $*" >&2; exit 1; }
aws_() { aws --region "$REGION" "$@"; }
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$@"; }
sslip() { echo "${1//./-}.sslip.io"; }

public_ip() {
  local ip
  ip=$(aws_ ec2 describe-instances --instance-ids "$INSTANCE_ID" \
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text 2>/dev/null) || die "cannot read the instance (aws credentials?)"
  [ -n "$ip" ] && [ "$ip" != "None" ] || die "instance $INSTANCE_ID has no public IP - is it stopped?"
  echo "$ip"
}
ssh_() { ssh -o ConnectTimeout=15 -o StrictHostKeyChecking=accept-new -i "$SSH_KEY" "ec2-user@$HOST" "$@"; }
current_tag() { ssh_ 'sudo docker inspect -f "{{.Config.Image}}" jannet-backend 2>/dev/null' | sed 's/.*://'; }

# Terraform's templatefile() semantics for this template: substitute the five
# known variables, refuse any other ${...}, then turn the "$${" escape into "${".
render() {
  python3 - "$TEMPLATE" "$1" "$REGION" "$GHCR_OWNER" <<'PY'
import re, sys
tpl, tag, region, owner = sys.argv[1:5]
s = open(tpl).read()
vals = {"project_name": "jannet-ai", "environment": "pilot-free", "aws_region": region,
        "ghcr_image_owner": owner, "app_version_tag": tag}
for k, v in vals.items():
    s = re.sub(r"(?<!\$)\$\{" + k + r"\}", v, s)
left = re.findall(r"(?<!\$)\$\{[^}]*\}", s)
if left:
    sys.exit("unrendered template expressions: " + ", ".join(sorted(set(left))))
sys.stdout.write(s.replace("$${", "${"))
PY
}

wait_backend() {
  local i c
  printf 'Waiting for the backend'
  for i in $(seq 1 48); do
    c=$(code "http://$HOST/api/v1/actuator/health")
    if [ "$c" = 401 ] || [ "$c" = 200 ]; then echo " - up"; return 0; fi
    printf '.'; sleep 15
  done
  echo; die "backend not up after 12 minutes - ssh in and check: sudo docker logs jannet-backend"
}

cmd_status() {
  HOST=${HOST:-$(public_ip)}
  local credits origin
  credits=$(aws_ ec2 describe-instance-credit-specifications --instance-ids "$INSTANCE_ID" \
            --query 'InstanceCreditSpecifications[0].CpuCredits' --output text 2>/dev/null)
  echo "EC2 $INSTANCE_ID  public IP $HOST  CPU credits: $credits"
  ssh_ 'for c in jannet-mysql jannet-ai-service jannet-backend; do
          sudo docker inspect -f "  {{.Name}}: {{.State.Health.Status}}, restarts {{.RestartCount}}, {{.Config.Image}}" $c
        done
        echo "  backend -> ai-service: $(sudo docker exec jannet-backend curl -s --max-time 10 http://ai-service:8001/health | head -c 70)"
        free -m | sed -n 2,3p | sed "s/^/  /"; df -h / | tail -1 | sed "s/^/  disk: /"' || echo "  (SSH failed)"
  origin=$(sed -nE 's/^ORIGIN_URL *= *"([^"]+)".*/\1/p' "$WORKER_DIR/wrangler.toml")
  [ "$origin" = "http://$(sslip "$HOST")" ] && echo "  Worker origin matches the current IP" \
    || echo "  WARNING: Worker points at $origin but the server is $HOST - run: $0 sync-ip"
  printf '  %-44s %s\n' "API via Worker (expect 401)" "$(code "$WORKER_URL/api/v1/actuator/health")"
  printf '  %-44s %s\n' "Worker non-API path (expect 404)" "$(code "$WORKER_URL/")"
  printf '  %-44s %s\n' "Server root, direct (expect 404)" "$(code "http://$HOST/")"
  printf '  %-44s %s\n' "Web app $PAGES_URL (expect 200)" "$(code "$PAGES_URL")"
  printf '  %-44s %s\n' "CORS allow-origin for the web app" "$(curl -s -i -X OPTIONS --max-time 20 "$WORKER_URL/api/v1/auth/login" \
      -H "Origin: $PAGES_URL" -H 'Access-Control-Request-Method: POST' | tr -d '\r' | sed -nE 's/^access-control-allow-origin: *//Ip')"
}

cmd_redeploy() {
  HOST=$(public_ip)
  local tag="${1:-}" f
  [ -n "$tag" ] || tag=$(current_tag)
  [ -n "$tag" ] || die "no tag given and none running - usage: $0 redeploy v0.1.2-pilot"
  git -C "$REPO" ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null || die "tag $tag is not on GitHub"
  echo "Redeploying $tag to $HOST"
  f=$(mktemp)
  render "$tag" > "$f" || { rm -f "$f"; die "render failed"; }
  bash -n "$f" || { rm -f "$f"; die "rendered bootstrap has a syntax error"; }
  scp -q -o ConnectTimeout=15 -i "$SSH_KEY" "$f" "ec2-user@$HOST:/tmp/bootstrap.sh" || { rm -f "$f"; die "scp failed"; }
  rm -f "$f"
  ssh_ 'sudo bash /tmp/bootstrap.sh > /tmp/bootstrap.log 2>&1; rc=$?; rm -f /tmp/bootstrap.sh; tail -1 /tmp/bootstrap.log; exit $rc' \
    || die "bootstrap failed - ssh in and read /tmp/bootstrap.log"
  wait_backend
  cmd_status
}

cmd_sync_ip() {
  HOST=$(public_ip)
  local want have
  want="http://$(sslip "$HOST")"
  have=$(sed -nE 's/^ORIGIN_URL *= *"([^"]+)".*/\1/p' "$WORKER_DIR/wrangler.toml")
  if [ "$have" = "$want" ]; then
    echo "Worker already points at $want"
  else
    sed -i -E "s#^ORIGIN_URL *= *\".*\"#ORIGIN_URL = \"$want\"#" "$WORKER_DIR/wrangler.toml"
    (cd "$WORKER_DIR" && npx --yes wrangler@4 deploy) || die "wrangler deploy failed"
    echo "Worker now points at $want - commit deployment/cloudflare/worker/wrangler.toml"
  fi
  echo "SSH: ssh -i $SSH_KEY ec2-user@$HOST"
  echo "Your public IP is $(curl -s --max-time 10 https://checkip.amazonaws.com) - SSH only works from admin_cidr in deployment/aws/terraform/pilot/terraform.tfvars"
  wait_backend
  cmd_status
}

cmd_deploy_web() {
  local api="$WORKER_URL/api/v1" cid
  echo "Building the Flutter web app with API_BASE_URL=$api (log: /tmp/flutter-build.log)"
  docker build -f "$REPO/docker/Dockerfile.flutter-web" --target build --build-arg API_BASE_URL="$api" \
    -t jannet-web-build:pilot "$REPO" > /tmp/flutter-build.log 2>&1 || { tail -25 /tmp/flutter-build.log; die "flutter build failed"; }
  rm -rf "$WEB_DIST" && mkdir -p "$WEB_DIST"
  cid=$(docker create jannet-web-build:pilot) || die "docker create failed"
  docker cp "$cid:/src/build/web/." "$WEB_DIST/" >/dev/null && docker rm -f "$cid" >/dev/null || die "docker cp failed"
  grep -q "${WORKER_URL#https://}" "$WEB_DIST/main.dart.js" || die "the Worker URL is not compiled into main.dart.js"
  (cd "$WEB_DIST" && npx --yes wrangler@4 pages deploy . --project-name "$PAGES_PROJECT" --branch main \
      --commit-hash "$(git -C "$REPO" rev-parse --short HEAD)" --commit-dirty=true) 2>&1 | tail -3
  printf '%s -> HTTP %s\n' "$PAGES_URL" "$(code "$PAGES_URL")"
}

cmd_set_proxy_key() {
  local k
  k=$(openssl rand -hex 32) || die "openssl missing"
  aws_ ssm put-parameter --name "$SSM_PATH/WORKER_PROXY_KEY" --type SecureString --overwrite --value "$k" >/dev/null \
    || die "could not store WORKER_PROXY_KEY in SSM"
  # wrangler may be the Windows build (called from WSL), where piping stdin into it is
  # unreliable - hand the secret over in a short-lived, owner-only file instead.
  local tmp="$WORKER_DIR/.proxy-key.$$.json" rc
  (umask 077; K="$k" python3 -c 'import json,os,sys; json.dump({"PROXY_KEY": os.environ["K"]}, open(sys.argv[1], "w"))' "$tmp")
  (cd "$WORKER_DIR" && npx --yes wrangler@4 secret bulk "$(basename "$tmp")" >/dev/null 2>&1); rc=$?
  rm -f "$tmp"; unset k
  [ $rc -eq 0 ] || die "could not set the Worker secret PROXY_KEY"
  echo "New proxy key stored in SSM ($SSM_PATH/WORKER_PROXY_KEY) and in the Worker (PROXY_KEY)."
  echo "nginx picks it up on the next: $0 redeploy"
}

cmd_backup() {
  HOST=$(public_ip)
  local dir="${PILOT_BACKUP_DIR:-$HOME/jannet-ai-backups}" f
  mkdir -p "$dir" && chmod 700 "$dir"
  f="$dir/jannet_ai_$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
  ssh_ 'sudo docker exec jannet-mysql sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysqldump -uroot --single-transaction --routines --triggers --no-tablespaces jannet_ai" | gzip' > "$f" \
    || { rm -f "$f"; die "mysqldump failed"; }
  gzip -dc "$f" | head -c 2000 | grep -q "MySQL dump" || { rm -f "$f"; die "backup looks empty"; }
  chmod 600 "$f"
  echo "Backup written: $f ($(du -h "$f" | cut -f1))"
}

case "${1:-}" in
  status)        HOST=$(public_ip); cmd_status ;;
  redeploy)      cmd_redeploy "${2:-}" ;;
  sync-ip)       cmd_sync_ip ;;
  deploy-web)    cmd_deploy_web ;;
  set-proxy-key) cmd_set_proxy_key ;;
  backup)        cmd_backup ;;
  render)        render "${2:-${PILOT_TAG:-v0.1.2-pilot}}" ;;
  *)             sed -n '2,14p' "$0"; exit 1 ;;
esac

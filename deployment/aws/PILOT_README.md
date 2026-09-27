# JanNet AI — AWS Free-Plan pilot runbook

Low-cost pilot of the full JanNet AI stack: one EC2 host (backend, AI service,
MySQL), the web app on Cloudflare Pages, a Cloudflare Worker for HTTPS, and
Brevo for e-mail. No domain is needed.

The full production design (RDS, S3, CloudWatch, GitHub OIDC) is still in
`deployment/aws/terraform/` and is **not** used by this pilot.

## URLs

| What | URL |
|---|---|
| Web app | https://jannet-ai.pages.dev |
| API (HTTPS, via Worker) | https://jannet-api.jennetai129012.workers.dev/api/v1 |
| API origin (plain HTTP, the server itself) | `http://<EC2 public IP>/api/v1` (run `pilot.sh status` to see the IP) |

Only use `https://jannet-ai.pages.dev`. Cloudflare preview links such as
`https://<hash>.jannet-ai.pages.dev` fail with a CORS error, because the
backend only allows the production origin (SSM `CORS_ALLOWED_ORIGINS`).

## How the request path works

```
Browser --HTTPS--> Cloudflare Pages (static Flutter web app)
Browser --HTTPS--> Cloudflare Worker jannet-api  (forwards /api/v1/* only)
                       |  adds X-JanNet-Client-IP + X-JanNet-Proxy-Key
                       v  plain HTTP, to <ip>.sslip.io (Workers cannot fetch bare IPs)
EC2 t3.small: nginx :80 --> backend :8080 (Spring Boot, prod profile)
                              |--> ai-service :8001 (FastAPI + YOLO, Docker network only)
                              '--> mysql :3306      (Docker volume docker_mysql_data)
Backend --SMTP 587 STARTTLS--> Brevo (e-mail OTP and admin MFA codes)
```

nginx only trusts the client IP sent by the Worker when the shared key matches.
The key is stored in SSM as `WORKER_PROXY_KEY` and in the Worker as the secret
`PROXY_KEY`. Anyone calling the server directly is recorded with their own
address, so the IP can't be spoofed.

## Operating the pilot: `deployment/scripts/pilot.sh`

Run from the repo root on your own machine. You need the AWS CLI (account
710404047578), the SSH key `~/.ssh/jannet-ai/jannet-ai-pilot.pem`, Docker, npx
(with `wrangler login` done), curl and python3. The script never prints secret
values.

| Task | Command |
|---|---|
| Health of every layer | `deployment/scripts/pilot.sh status` |
| Redeploy the running version | `deployment/scripts/pilot.sh redeploy` |
| Deploy a specific release | `deployment/scripts/pilot.sh redeploy v0.1.2-pilot` |
| After an EC2 stop/start (new IP) | `deployment/scripts/pilot.sh sync-ip` |
| Rebuild and publish the web app | `deployment/scripts/pilot.sh deploy-web` |
| Rotate the Worker/nginx key | `deployment/scripts/pilot.sh set-proxy-key` then `redeploy` |
| Database backup (to your machine) | `deployment/scripts/pilot.sh backup` |

`redeploy` renders `deployment/scripts/ec2-user-data-pilot.sh.tpl`, runs it
on the server over SSH (not `terraform apply`), and then waits for the backend
to come up. The script is idempotent: it re-clones the tag, rewrites `.env` from
SSM, pulls the GHCR images, runs `docker compose up -d` and rewrites nginx.

### Releasing a new backend / AI-service version

1. Merge to `main` and make sure CI is green.
2. `git tag -a vX.Y.Z-pilot -m "..." && git push origin vX.Y.Z-pilot`
   (`release-image-publish.yml` builds the images; its web-frontend job fails
   by design in the pilot, because the web app is published from `deploy-web`).
3. Wait for the "Build & publish images" job to succeed.
4. `deployment/scripts/pilot.sh redeploy vX.Y.Z-pilot`

### If the server's public IP changes

The pilot has no Elastic IP (to avoid the IPv4 charge on an idle address), so
the IP changes on every **stop/start**. A reboot keeps the same IP. After a
start, run `deployment/scripts/pilot.sh sync-ip`. It updates `ORIGIN_URL` in
`deployment/cloudflare/worker/wrangler.toml`, redeploys the Worker, and prints
the new SSH command. Then commit the updated `wrangler.toml`. The containers
start on their own after boot, so no redeploy is needed.

### If *your* IP changes (SSH stops working)

SSH (port 22) only allows `admin_cidr` from
`deployment/aws/terraform/pilot/terraform.tfvars`. Update it to
`<your ip>/32` (`curl https://checkip.amazonaws.com`), then run
`terraform plan` and, after review, `terraform apply` in that directory. Only
the security-group rule changes.

## Rollback

| Layer | How |
|---|---|
| Backend / AI service | `pilot.sh redeploy <previous tag>`. The pilot tags `v0.1.0`–`v0.1.2` added no database migrations, so rolling back among them is safe. Any newer tag that adds a Flyway migration is **forward-only**: restore a backup instead. |
| Web app | Cloudflare dashboard → Workers & Pages → `jannet-ai` → Deployments → pick the previous one → *Rollback*. |
| Worker | `cd deployment/cloudflare/worker && npx wrangler rollback` (choose the previous version). |
| Database | `gunzip -c ~/jannet-ai-backups/<file>.sql.gz \| ssh -i <key> ec2-user@<ip> 'sudo docker exec -i jannet-mysql sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot jannet_ai"'` (take a fresh `backup` first). |
| Configuration | Change the SSM parameter back, then run `pilot.sh redeploy`. |

## Configuration (SSM Parameter Store, `/jannet-ai/pilot-free/`)

SecureString: `AI_SERVICE_API_KEY`, `BOOTSTRAP_SUPER_ADMIN_PASSWORD`,
`DB_PASSWORD`, `JWT_SECRET`, `LOCAL_STORAGE_SIGNING_SECRET`,
`MYSQL_ROOT_PASSWORD`, `SMTP_PASSWORD`, `SMTP_USERNAME`, `WORKER_PROXY_KEY`.
String: `BOOTSTRAP_SUPER_ADMIN_EMAIL`, `BOOTSTRAP_SUPER_ADMIN_MOBILE`,
`CORS_ALLOWED_ORIGINS`, `NOTIFICATION_EMAIL_ENABLED`, `NOTIFICATION_EMAIL_FROM`,
`OTP_MFA_EMAIL_FALLBACK`, `SMTP_HOST`, `SMTP_PORT`.

Anything else falls back to the application default. The backend refuses to
start under the `prod` profile if a required value is missing or unsafe
(`ProductionSettingsCheck`). After changing a parameter, run `pilot.sh redeploy`.

E-mail goes through Brevo `smtp-relay.brevo.com:587` (STARTTLS, SMTP key). The
sender `NOTIFICATION_EMAIL_FROM` must stay a verified sender in Brevo. The free
plan allows 300 e-mails/day. SMS is disabled: citizens verify by e-mail, and
admin MFA codes go by e-mail while `OTP_MFA_EMAIL_FALLBACK=true`. Enabling SMS
(`NOTIFICATION_SMS_ENABLED=true` plus the `SMS_PROVIDER_*` settings) switches
admin MFA back to SMS automatically, as the SRS requires.

## Cost

See the deployment report for the current figures. In short, the account is on
the AWS **paid plan with promotional credits**, so EC2 compute, the EBS volume
and the public IPv4 address draw down the credits. They are not free forever.
Two AWS Budgets e-mail you when credits burn faster than expected and when any
real charge appears. Cloudflare (Workers, Pages) and Brevo stay on their free
plans.

## Known limitations

- Pilot only: no Elastic IP (the IP changes on stop/start), single host, no automated backups (use `pilot.sh backup`).
- Plain HTTP between Cloudflare and the server. Browser traffic is HTTPS up to Cloudflare, but not end-to-end TLS.
- The server can also be reached directly on port 80 (plain HTTP). The web app does not use this.
- MySQL TLS is required, but its self-signed certificate is not validated (the container is private to the host).
- The model weights are committed as a regular Git blob, not through Git LFS as `.gitattributes` intends.
- Cloudflare's bot check on `workers.dev` blocks some non-browser clients (e.g. Python's default `Python-urllib` user agent gets HTTP 403, error 1010). Browsers, the Flutter/Dart client (`Dart/...`), okhttp and curl were verified to get through.

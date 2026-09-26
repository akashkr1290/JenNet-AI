# nginx + TLS — Phase 22

`jannet.conf` is installed by `../scripts/ec2-user-data.sh.tpl` on first
boot and is the single reverse-proxy config for the app host. It never
proxies to `ai-service` (see `../aws/terraform/security_groups.tf`'s
header comment for why).

## First boot (audit fix Phase 07)

`jannet.conf` serves the web app at `/` (container on 127.0.0.1:3000) and
the API at `/api/v1/` (127.0.0.1:8080). Its 443 block needs certificate
files, so the bootstrap first installs `jannet-http.conf` (plain HTTP, same
routing, plus the ACME challenge path), obtains the certificate with
`certbot certonly --webroot -w /var/www/certbot`, and only then installs
`jannet.conf` and reloads. A `certbot-renew.timer` systemd unit renews the
certificate. Previously `jannet.conf` was installed first, nginx could not
start without the certificate, and the bootstrap stopped (`set -e`) before
certbot ran.

Without a domain (`domain_name` empty) or when certbot fails, nginx keeps
serving `jannet-http.conf`: plain HTTP on the public IP. This is a degraded
mode for demos only - SRS 27.2 requires TLS. Set the `CORS_ALLOWED_ORIGINS`
SSM parameter to the `http://<public-ip>` origin in that mode.

Both configs were checked with `nginx -t` (nginx 1.24) and with a routing
test against stub upstreams (fix-session ledger); not on a real instance.

## Manual certbot re-run (renewal failure, first-run failure, or a
## changed domain)

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d yourdomain.org --non-interactive --agree-tos -m you@yourdomain.org
sudo sed "s/__DOMAIN_NAME__/yourdomain.org/g" /opt/jannet-ai/repo/deployment/nginx/jannet.conf | sudo tee /etc/nginx/conf.d/jannet.conf >/dev/null
sudo nginx -t && sudo systemctl reload nginx
```

Renewal: the bootstrap installs `certbot-renew.timer` (pip-installed certbot
brings no timer of its own).

## Verifying TLS after setup

```bash
curl -Iv https://api.yourdomain.org/actuator/health
openssl s_client -connect api.yourdomain.org:443 -servername api.yourdomain.org </dev/null 2>/dev/null | openssl x509 -noout -dates
```

Both commands require a real, resolvable domain and a running instance
— **NOT run in this sandbox** (no live AWS resources exist here). See
`../VERIFICATION.md`.

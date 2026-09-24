# nginx + TLS — Phase 22

`jannet.conf` is installed by `../scripts/ec2-user-data.sh.tpl` on first
boot and is the single reverse-proxy config for the app host. It never
proxies to `ai-service` (see `../aws/terraform/security_groups.tf`'s
header comment for why).

## First boot without a domain yet

If `domain_name` is left empty in `terraform.tfvars`, the bootstrap
script skips the `certbot` step entirely and the HTTPS (443) `server`
block in `jannet.conf` will fail to start nginx (no certificate files
exist yet at the paths it references). In that case nginx serves the
plain-HTTP (80) block only. To operate this way intentionally (e.g. a
short-lived demo behind the raw EIP), comment out the entire `server {
listen 443 ... }` block before nginx first starts, and remove the
`return 301` redirect in the port-80 block so plain HTTP actually
reaches the backend. This is a deliberate, documented degraded mode, not
the recommended production configuration — SRS 27.2 requires TLS 1.2+
for all traffic.

## Manual certbot re-run (renewal failure, first-run failure, or a
## changed domain)

```bash
sudo certbot --nginx -d api.yourdomain.org --non-interactive --agree-tos -m you@yourdomain.org
sudo systemctl reload nginx
```

Certbot installs its own renewal systemd timer (`certbot.timer`) —
no additional cron job is needed or added by this project.

## Verifying TLS after setup

```bash
curl -Iv https://api.yourdomain.org/actuator/health
openssl s_client -connect api.yourdomain.org:443 -servername api.yourdomain.org </dev/null 2>/dev/null | openssl x509 -noout -dates
```

Both commands require a real, resolvable domain and a running instance
— **NOT run in this sandbox** (no live AWS resources exist here). See
`../VERIFICATION.md`.

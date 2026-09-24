# Load Testing

Gap-backlog Patch 54 (Sep 2026 audit). `locustfile.py` was verified to
load correctly and its fixture photo (`fixtures/test_photo.jpg`, a real
640×640 JPEG) was verified readable in this sandbox - it has **not** been
run against a live deployment, because none is reachable here.

## Run it for real

```
pip install locust
cd load-testing
# Edit locustfile.py: set TEST_MOBILE_NUMBERS to real pre-verified test
# accounts and TEST_PASSWORD to their shared password on the target
# environment (OTP-gated registration can't be scripted through without
# a real SMS/OTP backend - these must be seeded ahead of time).
locust -f locustfile.py --host=https://your-jannet-deployment.example.com
```

Open `http://localhost:8089` and run the three scenarios Gap-backlog
Patch 54 names: 100, 500, and 1000 concurrent users.

## What to measure

- API latency (p50/p95/p99) — Locust reports this natively per endpoint.
- Backend database load — cross-reference with RDS CloudWatch metrics
  during the run (Gap-backlog Patch 19's application-level metrics, plus
  standard RDS metrics).
- AI service load — ai-service's own `/api/v1/ai/monitoring/summary`
  (Gap-backlog Patch 36) during the run.
- Notification queue depth — once Gap-backlog Patch 15/17 (async
  notification processing) is implemented.

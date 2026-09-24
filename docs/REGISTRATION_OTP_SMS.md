# Registration OTP / SMS delivery

## Why the OTP never arrived

Registration generated and stored an OTP correctly, but no SMS was ever sent,
and the API still reported success:

1. **SMS was not configured.** The backend received `NOTIFICATION_SMS_ENABLED=false`
   and an empty `SMS_PROVIDER_URL` / `SMS_PROVIDER_API_KEY`. In that state
   `SmsGatewayClient.send()` logs an `[SMS-STUB]` line and returns normally.
2. **Nothing reported the failure.** `NotificationOtpDeliveryService` treated that
   normal return as success, so `POST /auth/register` answered **201 Created**,
   `POST /auth/resend-otp` answered **"OTP resent"**, and the app opened the OTP
   screen for a code that had been sent to no one.
3. **The number format was wrong for a real provider.** Registration stores Indian
   numbers as 10 national digits (`^[6-9]\d{9}$`), and that bare number was sent
   to the provider without a country code.
4. **The provider call had no timeouts.** A hung provider would block the
   registration request indefinitely.

Docker was **not** the cause: the backend service loads the whole root `.env`
through `env_file`, and `docker compose config` shows all SMS variables
arriving in the container. Their values disabled SMS.

## OTP flow (after the fix)

```
Flutter RegisterScreen ── POST /api/v1/auth/register ──► AuthService.register   (@Transactional)
                                                            ├─ save CITIZEN user
                                                            └─ OtpService.issueAndSend
                                                                 ├─ 6-digit code (SecureRandom)
                                                                 ├─ store BCrypt hash, 5-min expiry, 5 attempts
                                                                 └─ NotificationOtpDeliveryService.sendOtp
                                                                      ├─ SMS configured → SmsGatewayClient.send
                                                                      │     POST {to:"+91…", message} → provider
                                                                      └─ not configured → 503 (or [OTP-DEV] log, dev only)
   201 Created ◄── only if the provider accepted the message
   503 OTP_DELIVERY_FAILED ◄── otherwise; the new account is rolled back, so the citizen can register again

Flutter OtpVerificationScreen ── POST /api/v1/auth/verify-otp ──► OtpService.verifyAndConsume
                                                                   (expiry, attempt limit, hash match, consume)
                              ── POST /api/v1/auth/resend-otp ──► new OTP, same delivery rules
```

The Flutter app needed no change. It opens the OTP screen only after a
successful registration, and it shows the backend's error `message` otherwise.

## SMS provider

**No SMS vendor is implemented in this project**: not Twilio, MSG91, AWS SNS or
Firebase. The SRS names none ("external SMS/email/push gateways", 15.13). The
backend uses one generic HTTP contract:

```
POST ${SMS_PROVIDER_URL}
Authorization: Bearer ${SMS_PROVIDER_API_KEY}
Content-Type: application/json

{"to": "+919876543210", "message": "Your JanNet AI OTP for registration is 482913. It expires in 5 minutes. ..."}
```

Any 2xx response is treated as "accepted by the provider". `SMS_PROVIDER_URL`
must point at a provider API that accepts exactly this contract, or at a small
relay you run in front of your provider that translates it (for example to
form-encoded parameters, basic auth, a sender ID or a DLT template ID). Choosing
and connecting a vendor is a deployment decision; it cannot be solved in code
alone.

## Environment variables (names only)

| Variable | Purpose |
|---|---|
| `NOTIFICATION_SMS_ENABLED` | Must be `true` for real SMS |
| `SMS_PROVIDER_URL` | Endpoint accepting the contract above |
| `SMS_PROVIDER_API_KEY` | Sent as the bearer token |
| `SMS_DEFAULT_COUNTRY_CODE` | Added to stored 10-digit numbers (default `+91`) |
| `SMS_CONNECT_TIMEOUT_MS`, `SMS_READ_TIMEOUT_MS` | Provider timeouts (default 5000 / 10000) |
| `OTP_SMS_TEMPLATE` | Optional message text with `{otp}` `{purpose}` `{minutes}`; must contain `{otp}` |
| `OTP_DEV_LOG_CODE` | Development only; see "Local testing" |

All are listed in `.env.example` with placeholder values. Never commit a real `.env`.

## Docker

No Compose change is needed. The `backend` service reads the root `.env` through
`env_file`, so every variable above reaches the container. Check it with:

```bash
docker compose config | grep -E "NOTIFICATION_SMS_ENABLED|SMS_PROVIDER_URL|SMS_DEFAULT_COUNTRY_CODE|OTP_DEV_LOG_CODE"
```

After editing `.env`, recreate the backend: `docker compose up -d backend`.

## Local testing without an SMS account

Set `OTP_DEV_LOG_CODE=true` in `.env` and recreate the backend. With SMS not
configured, the OTP is then written to the backend log instead of failing:

```bash
docker compose logs backend | grep "OTP-DEV"
# [OTP-DEV] SMS not configured (...); DEVELOPMENT ONLY - registration OTP for ********10 is 482913
```

This is ignored under the `prod` profile (and pinned off in
`application-prod.yml`), and it never applies when SMS is configured. Leave it
`false` anywhere real users register.

## Real SMS testing requirements

These are provider or environment prerequisites, not code:

- **A provider account** with credit, plus an endpoint matching the contract
  above (or a relay).
- **India (TRAI DLT):** operators deliver an SMS only when it comes from a
  DLT-registered sender ID (header) and matches a DLT-registered content template
  word for word. Register the template, configure the sender ID and template ID
  at the provider or relay, and set `OTP_SMS_TEMPLATE` to the registered text.
  Without this the provider may accept the message and it will still not reach
  the phone.
- **A real test phone** to confirm arrival. `OTP_SMS_ACCEPTED` in the log means
  only that the provider accepted the request; handset delivery is not reported
  by this API.

## Failure behaviour

| Situation | Registration / resend (registration, MFA) / MFA login / admin-triggered reset | Forgot password, resend (password reset) |
|---|---|---|
| Provider accepted (2xx) | Normal success | Generic success |
| SMS not configured | **503 `OTP_DELIVERY_FAILED`** | Generic success (logged server-side) |
| Provider error / timeout / invalid number | **503 `OTP_DELIVERY_FAILED`** | Generic success (logged server-side) |
| Not configured + `OTP_DEV_LOG_CODE=true`, non-prod | Success; code in `[OTP-DEV]` log | Success; code in `[OTP-DEV]` log |

The user-facing 503 message is: "We could not send the verification code by SMS.
Please try again in a few minutes." Provider details, URLs, keys and full numbers
never appear in API responses. Server logs carry `OTP_DELIVERY_FAILED` with the
reason, a masked number (`********10`) and variable names only. A failed
registration rolls back the new account, so registering again works.

The password-reset paths answer identically whether or not an account exists, so
a delivery failure there cannot reveal which numbers are registered. For the same
reason `OtpService.issueAndSend` is `noRollbackFor = OtpDeliveryException.class`.

OTP codes are never logged, except in the explicit development mode above.

## Known deployment gap (not changed here)

`deployment/scripts/ec2-user-data.sh.tpl` writes `SMS_PROVIDER_API_KEY` into the
production `.env`, but not `NOTIFICATION_SMS_ENABLED` or `SMS_PROVIDER_URL`. An
AWS deployment from that template would therefore have SMS disabled, and
registration there would now return 503. Add those variables to the deployment
before relying on registration in production.

# Notification delivery configuration (SMS, e-mail, push)

Covers audit findings GAP-002, GAP-003, GAP-004, GAP-005, GAP-006, GAP-022 and GAP-024.
**Nothing in this repository sends a real SMS, e-mail or push message by itself.** Each
channel needs an external account that the operator provides. This document lists
exactly what to provision and which variables to set. Put secret values in `.env`
locally and in SSM Parameter Store in AWS (`deployment/ssm/PARAMETERS.md`), never in git.

## What happens when a channel is not configured

| Situation | Behaviour |
|---|---|
| OTP by SMS, SMS not configured | `503 OTP_DELIVERY_FAILED`; registration is rolled back. With `OTP_DEV_LOG_CODE=true` (non-prod profiles only) the code is written to the backend log as `[OTP-DEV]`. |
| OTP by e-mail, e-mail not configured | Same as above, for the e-mail channel. |
| Complaint notification, channel not configured or no address/device | A `notifications` row with `delivery_status = SKIPPED` and `delivery_attempts = 0` (GAP-022). Previously these rows said `DELIVERED`. |
| Provider rejects, times out or is unreachable | Retried up to `NOTIFICATION_MAX_DELIVERY_ATTEMPTS` (max 3) with exponential backoff, then `FAILED`. |

"Sent" means the provider (SMTP server, SMS API, FCM) **accepted** the message. None of
these APIs confirms delivery to the handset or mailbox.

## SMS (GAP-002, GAP-003)

The SRS names no SMS vendor, so none is hard-coded. SMS goes through an `SmsProvider`
adapter chosen by `SMS_PROVIDER`. The built-in `generic-http` adapter is configured
only through the variables below. A provider whose API cannot be expressed this way
(SOAP, SMPP, signed requests) needs a new `SmsProvider` implementation (one Spring bean
with its own id). No other class changes.

### External prerequisites (India)

1. An account with an SMS provider that offers an HTTP API.
2. TRAI DLT registration (done through the provider or a DLT portal):
   - the principal entity ID;
   - a sender ID (header);
   - two content templates, one for the OTP text and one for the notification texts.
3. The OTP template must match `OTP_SMS_TEMPLATE` exactly. Placeholders are `{otp}`,
   `{purpose}` and `{minutes}`. Leave `OTP_SMS_TEMPLATE` empty to use the built-in text,
   and register that text instead.
4. Complaint notification texts come from
   `backend/.../service/notification/NotificationTemplates.java`, in English and Hindi.
   Register the variants you intend to send by SMS. If you send Hindi, check whether your
   provider needs a Unicode flag. Pass that flag through `APP_NOTIFICATION_SMS_EXTRAPARAMS_<NAME>`.

### Variables

| Variable | Meaning | Default |
|---|---|---|
| `NOTIFICATION_SMS_ENABLED` | Master switch | `false` |
| `SMS_PROVIDER` | Adapter id | `generic-http` |
| `SMS_PROVIDER_URL` | Provider endpoint (POST) | empty |
| `SMS_PROVIDER_API_KEY` | API key / token / password (**secret**) | empty |
| `SMS_PROVIDER_REQUEST_FORMAT` | `JSON` or `FORM` (x-www-form-urlencoded) | `JSON` |
| `SMS_PROVIDER_AUTH_SCHEME` | `BEARER` (`Authorization: Bearer key`), `HEADER` (key in `SMS_PROVIDER_AUTH_HEADER`), `BASIC` (`SMS_PROVIDER_BASIC_USERNAME`:key), `QUERY` (`?<SMS_PROVIDER_AUTH_QUERY_PARAM>=key`), `NONE` | `BEARER` |
| `SMS_PROVIDER_NUMBER_FORMAT` | `E164` (+919876543210), `DIGITS` (919876543210), `NATIONAL` (9876543210) | `E164` |
| `SMS_PROVIDER_TO_FIELD` / `SMS_PROVIDER_MESSAGE_FIELD` | Request field names | `to` / `message` |
| `SMS_PROVIDER_SENDER_FIELD` + `SMS_SENDER_ID` | Sender ID field and value | not sent |
| `SMS_DLT_ENTITY_ID_FIELD` + `SMS_DLT_ENTITY_ID` | DLT entity ID field and value | not sent |
| `SMS_DLT_TEMPLATE_ID_FIELD` + `SMS_OTP_DLT_TEMPLATE_ID` / `SMS_NOTIFICATION_DLT_TEMPLATE_ID` | DLT template ID field, and the ID used for OTP and for notification texts | not sent |
| `APP_NOTIFICATION_SMS_EXTRAPARAMS_<NAME>` | Extra fixed parameter `<name>` (lower-cased by Spring). It never overrides the fields above. | none |
| `SMS_PROVIDER_SUCCESS_PATTERN` | Regex the 2xx response body must match. Use it for providers that return HTTP 200 with an error in the body. | not checked |
| `SMS_DEFAULT_COUNTRY_CODE`, `SMS_CONNECT_TIMEOUT_MS`, `SMS_READ_TIMEOUT_MS` | As before | `+91`, 5000, 10000 |

### Worked example (illustrative, not a real provider)

Suppose a provider documents:

```
POST https://api.example-sms.test/send
Content-Type: application/x-www-form-urlencoded
X-Auth-Key: <key>
mobile=919876543210&text=...&sender=ABCDEF&pe_id=...&template_id=...
```

That maps to:

```
SMS_PROVIDER_URL=https://api.example-sms.test/send
SMS_PROVIDER_REQUEST_FORMAT=FORM
SMS_PROVIDER_AUTH_SCHEME=HEADER
SMS_PROVIDER_AUTH_HEADER=X-Auth-Key
SMS_PROVIDER_NUMBER_FORMAT=DIGITS
SMS_PROVIDER_TO_FIELD=mobile
SMS_PROVIDER_MESSAGE_FIELD=text
SMS_PROVIDER_SENDER_FIELD=sender
SMS_SENDER_ID=ABCDEF
SMS_DLT_ENTITY_ID_FIELD=pe_id
SMS_DLT_TEMPLATE_ID_FIELD=template_id
```

`SmsGatewayClientTest` checks this request shape against a mocked provider
(`MockRestServiceServer`). Nothing is sent to a real provider.

### Verifying a real provider (manual, after provisioning)

1. Set the variables and restart the backend. The startup log must not show
   `[SMS-STUB]`.
2. Register a test citizen with your own phone number. `OTP_SMS_ACCEPTED` in the log
   means the provider accepted the message.
3. Confirm the SMS actually arrives on the phone. The system cannot verify this step.

## E-mail (GAP-004, GAP-005)

- **Provision:** an SMTP relay (for example Amazon SES SMTP credentials, or an
  institutional relay) with a verified sender address/domain.
- **Set:**
  - `NOTIFICATION_EMAIL_ENABLED=true`
  - `NOTIFICATION_EMAIL_FROM`
  - `SMTP_HOST`, `SMTP_PORT`
  - `SMTP_USERNAME`, `SMTP_PASSWORD` (**secret**)
  - optionally `MANAGEMENT_HEALTH_MAIL_ENABLED=true`, so health reflects SMTP.
- **E-mail OTP (GAP-004):**
  - Registration can send the code by e-mail with `otpChannel: "EMAIL"` on
    `POST /auth/register`; the Flutter registration screen has an "SMS | Email"
    selector for this.
  - Password reset (`channel` on `POST /auth/forgot-password`) and resend
    (`channel` on `POST /auth/resend-otp`) can also use e-mail.
  - Verifying an e-mailed registration code sets `users.email_verified_at`.
  - Admin MFA stays on SMS.

## Push (GAP-006) - NOT complete

- **Backend: ready.** The following are all in place:
  - `PushGatewayClient` (Firebase Admin SDK);
  - the `device_tokens` table;
  - `POST/DELETE /api/v1/notifications/device-token`;
  - the preference toggle.
- **Enable it by:**
  1. creating a Firebase project and a service account key (JSON);
  2. mounting that JSON as a secret file;
  3. setting `NOTIFICATION_PUSH_ENABLED=true` and `FIREBASE_CREDENTIALS_PATH=<path>`.
- **Flutter: not implemented.** The app has no `firebase_core`/`firebase_messaging`
  dependency and never registers a token. Adding them needs all of the following:
  - `flutter pub add firebase_core firebase_messaging`, plus a regenerated `pubspec.lock`;
  - `google-services.json` / `GoogleService-Info.plist` from the Firebase project;
  - token registration after login and deregistration on logout.

  This fix session could not do that (no Flutter SDK or pub access, and no Firebase
  project). Until it is done, push rows are `SKIPPED` ("no device token").

## Localisation (GAP-024)

Every notification text is rendered from `NotificationTemplates` in the recipient's
`personal_language` setting: `EN` (default) or `HI`.

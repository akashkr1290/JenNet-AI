package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bound to {@code app.notification.*} in application.yml (Phase 15,
 * Notification Module, SRS 15.13). Mirrors {@link AiServiceProperties}'s
 * "master switch defaults safe for an unconfigured sandbox" style: both
 * {@link Email#enabled} and {@link Sms#enabled} default to {@code false}
 * so this backend boots and runs its full test suite in this workspace
 * (no outbound network, no real SMTP/SMS credentials) without attempting
 * a real delivery - see {@code EmailGatewayClient}/{@code SmsGatewayClient}
 * for exactly what happens when a channel is disabled (a logged
 * "would send" stub, same honesty convention as the Phase 4
 * LoggingOtpDeliveryService this phase replaces).
 */
@ConfigurationProperties(prefix = "app.notification")
public class NotificationProperties {

    @NestedConfigurationProperty
    private Email email = new Email();

    @NestedConfigurationProperty
    private Sms sms = new Sms();

    @NestedConfigurationProperty
    private Push push = new Push();

    /**
     * SRS 15.13 Validation Rules: "delivery failures are retried up to 3
     * times ... before being logged as failed" - matches
     * notifications.delivery_attempts' CHECK (BETWEEN 0 AND 3) exactly
     * (V11__create_notifications.sql); not meant to be raised past 3
     * without a corresponding migration.
     */
    private int maxDeliveryAttempts = 3;

    /**
     * Base delay for the exponential backoff between retry attempts.
     * Deliberately small (not the many-seconds-per-attempt a real SMS/
     * email provider integration might use) because {@link
     * com.jannetai.backend.service.notification.NotificationService}
     * dispatches synchronously on the caller's request thread today (a
     * citizen/officer action) - see that class's Javadoc "KNOWN
     * LIMITATION: synchronous dispatch" for why moving this to an async
     * queue is documented as a follow-up, not implemented speculatively
     * this phase. No SRS-given number exists for this value (same
     * "documented placeholder" convention as several Phase 6/8/11
     * numeric defaults already in this codebase).
     */
    private long retryBackoffBaseMs = 200L;

    /**
     * Gap-backlog Patch 17 (Sep 2026 strict recheck): when true, EMAIL/SMS/PUSH
     * delivery (with its retries/backoff) runs on the bounded
     * {@code notificationExecutor} pool after the caller's transaction
     * commits, instead of on the citizen/officer request thread. IN_APP stays
     * synchronous (persisting the row IS the delivery). Defaults to false
     * here so a bare {@code new NotificationProperties()} (unit tests) keeps
     * the original synchronous semantics; application.yml turns it on.
     */
    private boolean asyncEnabled = false;

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    public void setAsyncEnabled(boolean asyncEnabled) {
        this.asyncEnabled = asyncEnabled;
    }

    public Email getEmail() {
        return email;
    }

    public void setEmail(Email email) {
        this.email = email;
    }

    public Sms getSms() {
        return sms;
    }

    public void setSms(Sms sms) {
        this.sms = sms;
    }

    public Push getPush() {
        return push;
    }

    public void setPush(Push push) {
        this.push = push;
    }

    public int getMaxDeliveryAttempts() {
        return maxDeliveryAttempts;
    }

    /**
     * Audit GAP-056: notifications.delivery_attempts has CHECK (BETWEEN 0 AND 3)
     * (V11) and SRS 15.13 says "retried up to 3 times". A configured value above
     * 3 used to make EVERY notification insert fail (in the async thread - the
     * row was silently lost). The value is now clamped to 1..3 with a warning.
     */
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
        int clamped = Math.max(1, Math.min(MAX_DELIVERY_ATTEMPTS_ALLOWED, maxDeliveryAttempts));
        if (clamped != maxDeliveryAttempts) {
            org.slf4j.LoggerFactory.getLogger(NotificationProperties.class).warn(
                    "NOTIFICATION_MAX_DELIVERY_ATTEMPTS={} is outside 1..{} (notifications.delivery_attempts CHECK, "
                            + "SRS 15.13) - using {}", maxDeliveryAttempts, MAX_DELIVERY_ATTEMPTS_ALLOWED, clamped);
        }
        this.maxDeliveryAttempts = clamped;
    }

    /** Upper bound of notifications.delivery_attempts (V11 CHECK BETWEEN 0 AND 3). */
    public static final int MAX_DELIVERY_ATTEMPTS_ALLOWED = 3;

    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public void setRetryBackoffBaseMs(long retryBackoffBaseMs) {
        this.retryBackoffBaseMs = retryBackoffBaseMs;
    }

    public static class Email {
        /** Master switch - see class Javadoc. */
        private boolean enabled = false;

        private String fromAddress = "no-reply@jannetai.local";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
        }
    }

    public static class Sms {
        /** Master switch - see class Javadoc. */
        private boolean enabled = false;

        /**
         * Generic webhook-style endpoint: POST {"to": "...", "message": "..."}
         * with the api key as a bearer token. No specific SMS provider is
         * named by the SRS (15.13 Dependencies: "external SMS/email/push
         * gateways", generic) - a real deployment points this at whichever
         * provider it has a contract with.
         */
        private String providerUrl = "";

        private String providerApiKey = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderUrl() {
            return providerUrl;
        }

        public void setProviderUrl(String providerUrl) {
            this.providerUrl = providerUrl;
        }

        public String getProviderApiKey() {
            return providerApiKey;
        }

        public void setProviderApiKey(String providerApiKey) {
            this.providerApiKey = providerApiKey;
        }

        /**
         * Registration OTP fix: country code added to stored 10-digit national
         * numbers when sending (see SmsNumberFormatter). Default India (+91).
         */
        private String defaultCountryCode = "+91";

        /** Registration OTP fix: the provider call previously had no timeouts at all. */
        private int connectTimeoutMs = 5000;

        private int readTimeoutMs = 10000;

        public String getDefaultCountryCode() {
            return defaultCountryCode;
        }

        public void setDefaultCountryCode(String defaultCountryCode) {
            this.defaultCountryCode = defaultCountryCode;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        // ---- Audit GAP-003: provider adapter settings (docs/SMS_PROVIDER_CONFIGURATION.md) ----

        /** SMS adapter id - see SmsProvider. Only "generic-http" is built in. */
        private String provider = "generic-http";

        /** JSON or FORM (application/x-www-form-urlencoded). */
        private String requestFormat = "JSON";

        /** BEARER, HEADER, BASIC, QUERY or NONE - see SmsRequestBuilder. */
        private String authScheme = "BEARER";

        /** Header carrying the API key when authScheme=HEADER. */
        private String authHeaderName = "Authorization";

        /** Query parameter carrying the API key when authScheme=QUERY. */
        private String authQueryParam = "apikey";

        /** User name for authScheme=BASIC (the API key is the password). */
        private String basicUsername = "";

        /** E164 (+919876543210), DIGITS (919876543210) or NATIONAL (9876543210). */
        private String numberFormat = "E164";

        /** Request field for the recipient number. */
        private String toField = "to";

        /** Request field for the message text. */
        private String messageField = "message";

        /** Request field for the sender ID / header (blank = not sent). */
        private String senderIdField = "";

        /** Sender ID / header registered with the provider (India: DLT header). */
        private String senderId = "";

        /** Request field for the DLT principal entity ID (blank = not sent). */
        private String dltEntityIdField = "";

        /** DLT principal entity ID. */
        private String dltEntityId = "";

        /** Request field for the DLT content template ID (blank = not sent). */
        private String dltTemplateIdField = "";

        /** DLT template ID registered for the OTP text (OTP_SMS_TEMPLATE). */
        private String otpDltTemplateId = "";

        /** DLT template ID registered for complaint notification texts. */
        private String notificationDltTemplateId = "";

        /** Optional regex the 2xx response body must match to count as accepted. */
        private String successBodyPattern = "";

        /** Additional fixed request parameters (e.g. route, unicode flag); never override the fields above. */
        private Map<String, String> extraParams = new LinkedHashMap<>();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getRequestFormat() {
            return requestFormat;
        }

        public void setRequestFormat(String requestFormat) {
            this.requestFormat = requestFormat;
        }

        public String getAuthScheme() {
            return authScheme;
        }

        public void setAuthScheme(String authScheme) {
            this.authScheme = authScheme;
        }

        public String getAuthHeaderName() {
            return authHeaderName;
        }

        public void setAuthHeaderName(String authHeaderName) {
            this.authHeaderName = authHeaderName;
        }

        public String getAuthQueryParam() {
            return authQueryParam;
        }

        public void setAuthQueryParam(String authQueryParam) {
            this.authQueryParam = authQueryParam;
        }

        public String getBasicUsername() {
            return basicUsername;
        }

        public void setBasicUsername(String basicUsername) {
            this.basicUsername = basicUsername;
        }

        public String getNumberFormat() {
            return numberFormat;
        }

        public void setNumberFormat(String numberFormat) {
            this.numberFormat = numberFormat;
        }

        public String getToField() {
            return toField;
        }

        public void setToField(String toField) {
            this.toField = toField;
        }

        public String getMessageField() {
            return messageField;
        }

        public void setMessageField(String messageField) {
            this.messageField = messageField;
        }

        public String getSenderIdField() {
            return senderIdField;
        }

        public void setSenderIdField(String senderIdField) {
            this.senderIdField = senderIdField;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId;
        }

        public String getDltEntityIdField() {
            return dltEntityIdField;
        }

        public void setDltEntityIdField(String dltEntityIdField) {
            this.dltEntityIdField = dltEntityIdField;
        }

        public String getDltEntityId() {
            return dltEntityId;
        }

        public void setDltEntityId(String dltEntityId) {
            this.dltEntityId = dltEntityId;
        }

        public String getDltTemplateIdField() {
            return dltTemplateIdField;
        }

        public void setDltTemplateIdField(String dltTemplateIdField) {
            this.dltTemplateIdField = dltTemplateIdField;
        }

        public String getOtpDltTemplateId() {
            return otpDltTemplateId;
        }

        public void setOtpDltTemplateId(String otpDltTemplateId) {
            this.otpDltTemplateId = otpDltTemplateId;
        }

        public String getNotificationDltTemplateId() {
            return notificationDltTemplateId;
        }

        public void setNotificationDltTemplateId(String notificationDltTemplateId) {
            this.notificationDltTemplateId = notificationDltTemplateId;
        }

        public String getSuccessBodyPattern() {
            return successBodyPattern;
        }

        public void setSuccessBodyPattern(String successBodyPattern) {
            this.successBodyPattern = successBodyPattern;
        }

        public Map<String, String> getExtraParams() {
            return extraParams;
        }

        public void setExtraParams(Map<String, String> extraParams) {
            this.extraParams = extraParams == null ? new LinkedHashMap<>() : extraParams;
        }
    }

    /** Gap-backlog Patch 14/16 (Sep 2026 audit): real FCM push - see {@code PushGatewayClient}. */
    public static class Push {
        /** Master switch - see class Javadoc. Same "off by default, logged stub instead" convention as Email/Sms. */
        private boolean enabled = false;

        /**
         * Absolute path to the Firebase service-account JSON credentials
         * file (downloaded from Firebase Console > Project Settings >
         * Service Accounts > Generate new private key). Never committed
         * to source control - same "no secret values in source control"
         * convention as every other credential in this project
         * (deployment/aws/terraform/github_oidc.tf's own header comment).
         */
        private String credentialsPath = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCredentialsPath() {
            return credentialsPath;
        }

        public void setCredentialsPath(String credentialsPath) {
            this.credentialsPath = credentialsPath;
        }
    }
}

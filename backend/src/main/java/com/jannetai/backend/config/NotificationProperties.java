package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

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

    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
        this.maxDeliveryAttempts = maxDeliveryAttempts;
    }

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

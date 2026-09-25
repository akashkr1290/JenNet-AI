package com.jannetai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Audit GAP-010: asynchronous AI processing queue ({@code app.ai-processing.*}).
 * Defaults follow SRS 15.3 ("3 retries then Verification Team"); the delays
 * are documented placeholders (the SRS gives no number).
 */
@Component
@ConfigurationProperties(prefix = "app.ai-processing")
public class AiProcessingProperties {

    /** false = process on the request thread after commit (old timing, still with retries via the sweeper). */
    private boolean asyncEnabled = true;

    /** Attempts in total before the complaint is handed to the Verification Team. */
    private int maxAttempts = 3;

    /** Backoff base: 30 s, 60 s, 120 s ... */
    private long retryBaseDelaySeconds = 30;

    /** How long a worker may hold a job before the sweeper assumes it died. */
    private long leaseSeconds = 600;

    /** Complaints at AI_PROCESSING with no job for this long are picked up by the sweeper. */
    private long orphanGraceSeconds = 120;

    /** Max jobs started per sweep. */
    private int sweepBatchSize = 20;

    public boolean isAsyncEnabled() { return asyncEnabled; }
    public void setAsyncEnabled(boolean asyncEnabled) { this.asyncEnabled = asyncEnabled; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = Math.max(1, maxAttempts); }
    public long getRetryBaseDelaySeconds() { return retryBaseDelaySeconds; }
    public void setRetryBaseDelaySeconds(long retryBaseDelaySeconds) { this.retryBaseDelaySeconds = Math.max(1, retryBaseDelaySeconds); }
    public long getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(long leaseSeconds) { this.leaseSeconds = Math.max(60, leaseSeconds); }
    public long getOrphanGraceSeconds() { return orphanGraceSeconds; }
    public void setOrphanGraceSeconds(long orphanGraceSeconds) { this.orphanGraceSeconds = Math.max(0, orphanGraceSeconds); }
    public int getSweepBatchSize() { return sweepBatchSize; }
    public void setSweepBatchSize(int sweepBatchSize) { this.sweepBatchSize = Math.max(1, sweepBatchSize); }
}

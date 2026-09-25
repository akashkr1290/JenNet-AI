package com.jannetai.backend.service.complaint;

import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.repository.AuditLogRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.AuditJson;
import com.jannetai.backend.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Audit GAP-029 (SRS 15.1 business rule: "reputation score increases with
 * verified genuine complaints and decreases with confirmed fraudulent
 * submissions"; BO-7; risk table "Fraudulent submissions"). The score was
 * displayed but never changed - every account stayed at 100.
 *
 * <ul>
 *   <li>A complaint reaching VERIFIED (auto or manual) counts once as
 *       genuine: +{@code app.reputation.genuine-delta}.</li>
 *   <li>A complaint REJECTED with a fraud reason code
 *       ({@code app.reputation.fraud-reason-codes}, default SPAM_OR_ABUSE - the
 *       fraud/abuse code offered by the verification screen) counts once as
 *       fraudulent: {@code app.reputation.fraud-delta}.</li>
 * </ul>
 * Each complaint affects the score at most once per kind (idempotent via the
 * audit trail), so a complaint re-verified after an approved appeal is not
 * double-counted. The result is kept within users.reputation_score's CHECK
 * range 0-1000. The SRS gives no step sizes; the defaults are documented
 * placeholders for the Admin to tune.
 */
@Service
public class ReputationService {

    static final String GENUINE_ACTION = "REPUTATION_GENUINE_COMPLAINT";
    static final String FRAUD_ACTION = "REPUTATION_FRAUDULENT_COMPLAINT";
    static final String FRAUD_REVERSED_ACTION = "REPUTATION_FRAUD_OVERTURNED_ON_APPEAL";
    static final int MIN_SCORE = 0;
    static final int MAX_SCORE = 1000;

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final AuditLogRepository auditLogRepository;

    @Value("${app.reputation.genuine-delta:5}")
    private int genuineDelta = 5;

    @Value("${app.reputation.fraud-delta:-20}")
    private int fraudDelta = -20;

    @Value("${app.reputation.fraud-reason-codes:SPAM_OR_ABUSE}")
    private String fraudReasonCodes = "SPAM_OR_ABUSE";

    public ReputationService(UserRepository userRepository, AuditService auditService,
                             AuditLogRepository auditLogRepository) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.auditLogRepository = auditLogRepository;
    }

    /** Pure: the new score after applying a delta within the allowed range. */
    static int adjusted(int current, int delta) {
        long next = (long) current + delta;
        return (int) Math.max(MIN_SCORE, Math.min(MAX_SCORE, next));
    }

    public boolean isFraudReasonCode(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return false;
        }
        Set<String> codes = Arrays.stream(fraudReasonCodes.split(","))
                .map(String::trim).filter(c -> !c.isEmpty()).collect(Collectors.toSet());
        return codes.contains(reasonCode.trim());
    }

    /** The complaint reached VERIFIED: once per complaint, +genuine-delta. */
    @Transactional
    public void onVerifiedGenuine(Complaint complaint) {
        apply(complaint, GENUINE_ACTION, genuineDelta, null);
    }

    /** The complaint was REJECTED: counts only for a fraud reason code. */
    @Transactional
    public void onRejected(Complaint complaint, String reasonCode) {
        if (isFraudReasonCode(reasonCode)) {
            apply(complaint, FRAUD_ACTION, fraudDelta, reasonCode);
        }
    }

    /**
     * Audit GAP-030: an APPROVED appeal means the fraud was not confirmed after
     * all - the penalty recorded for this complaint (if any) is given back, once.
     */
    @Transactional
    public void onFraudOverturned(Complaint complaint, String previousReasonCode) {
        boolean penalised = !auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                "COMPLAINT", complaint.getComplaintId(), FRAUD_ACTION).isEmpty();
        if (penalised) {
            apply(complaint, FRAUD_REVERSED_ACTION, -fraudDelta, previousReasonCode);
        }
    }

    private void apply(Complaint complaint, String action, int delta, String reasonCode) {
        User citizen = complaint.getCitizen();
        if (citizen == null || delta == 0) {
            return;
        }
        boolean alreadyCounted = !auditLogRepository.findByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtAsc(
                "COMPLAINT", complaint.getComplaintId(), action).isEmpty();
        if (alreadyCounted) {
            return;
        }
        int before = citizen.getReputationScore() == null ? 100 : citizen.getReputationScore();
        int after = adjusted(before, delta);
        citizen.setReputationScore(after);
        userRepository.save(citizen);
        auditService.record(null, action, "COMPLAINT", complaint.getComplaintId(),
                AuditJson.of("citizen_id", citizen.getUserId(), "before", before, "after", after,
                        "delta", delta, "reason_code", reasonCode));
        log.info("Reputation of citizen {} {} -> {} ({})", citizen.getUserId(), before, after, action);
    }
}

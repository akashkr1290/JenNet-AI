package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import com.jannetai.backend.dto.notification.NotificationPreferencesResponse;
import com.jannetai.backend.dto.notification.NotificationResponse;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.DeviceToken;
import com.jannetai.backend.entity.Notification;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import com.jannetai.backend.entity.enums.DevicePlatform;
import com.jannetai.backend.entity.enums.NotificationChannel;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.NotificationRepository;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.repository.UserRepository;
import com.jannetai.backend.service.notification.NotificationTemplates.Event;
import com.jannetai.backend.service.settings.PersonalSettingKey;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Phase 15 (Notification Module, SRS 15.13). Owns:
 *  - persisting/dispatching notifications for the three trigger types the
 *    SRS names (citizen status-change alerts, officer new-assignment
 *    alerts, officer SLA-breach warnings at 80%);
 *  - the user's own notification list (SRS 20.5 GET /api/v1/notifications);
 *  - reading/writing this user's channel preferences (USER-scoped
 *    {@code settings} rows via {@link NotificationPreferenceKey}).
 *
 * CALL SITES (documented here since they're spread across three
 * pre-existing files, none of which previously had any notification
 * hook): {@code ComplaintService#recordHistory} (covers verify/
 * updateStatus/reopen/reassign's own status transitions),
 * {@code DepartmentAssignmentService#assignAndApply} (the auto-assign
 * path - a *separate* private recordHistory from ComplaintService's, so
 * needed its own explicit call), {@code ComplaintService#reassign} (officer-
 * specific "new complaint assigned" alert, distinct from the citizen
 * status alert), and {@code EscalationSchedulerService#sweepForSlaWarnings}
 * (new Phase 15 method alongside the existing Phase 11 breach sweep).
 *
 * (Gap-backlog Patch 17: now asynchronous when app.notification.async-enabled
 * is true - see dispatch(). Original note kept for history:) KNOWN LIMITATION: synchronous dispatch. {@link #dispatch} runs on the
 * caller's own request thread (a citizen/officer HTTP request, or the
 * escalation scheduler's thread) with a real (if short) retry backoff -
 * see {@link NotificationProperties#getRetryBackoffBaseMs()}'s Javadoc.
 * A production deployment sending real SMS/email at volume should move
 * this to an async queue; not implemented this phase (no message broker
 * exists in this stack, and introducing one is out of Phase 15's scope) -
 * documented here and in PHASE_HANDOFF.md rather than silently accepted.
 *
 * Gap-backlog Patch 14/16 (Sep 2026 audit): PUSH is now actually
 * dispatched via {@link PushGatewayClient} (Firebase Cloud Messaging),
 * closing the gap this comment used to document (device-token storage
 * now exists - see the {@code device_tokens} table /
 * {@code DeviceTokenRepository}, and the registration endpoint on
 * {@code NotificationController}). Same "off by default, logs a stub
 * instead" convention as SMS/Email until {@code app.notification.push.*}
 * is configured with a real Firebase service account - see
 * {@link PushGatewayClient}'s Javadoc.
 *
 * Audit fixes (Sep 2026 fix session):
 * <ul>
 *   <li>GAP-022: a channel that transmitted nothing (disabled, no address,
 *       no device) is recorded as {@code SKIPPED}, never {@code DELIVERED}.</li>
 *   <li>GAP-023: the remaining SRS 14.1/15.13 events - closure and duplicate
 *       merge (citizen), appeal outcome (citizen), SLA escalation (Department
 *       Head + officer), possible duplicate needing manual review
 *       (Verification Team), and no officer available (Department Head).</li>
 *   <li>GAP-024: every text comes from {@link NotificationTemplates} in the
 *       recipient's own language (EN/HI personal setting).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** SRS 15.13: the "major status changes" citizens are alerted on. */
    private static final Set<ComplaintStatus> CITIZEN_NOTIFIABLE_STATUSES = EnumSet.of(
            ComplaintStatus.SUBMITTED,
            ComplaintStatus.VERIFIED,
            ComplaintStatus.ASSIGNED,
            ComplaintStatus.IN_PROGRESS,
            ComplaintStatus.RESOLVED,
            ComplaintStatus.REJECTED,
            // Audit GAP-023: closure (after the grace period) and duplicate merge.
            ComplaintStatus.CLOSED,
            ComplaintStatus.DUPLICATE);

    private final NotificationRepository notificationRepository;
    private final SettingRepository settingRepository;
    private final NotificationProperties properties;
    private final EmailGatewayClient emailGatewayClient;
    private final SmsGatewayClient smsGatewayClient;
    private final PushGatewayClient pushGatewayClient;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;         // audit GAP-023: Verification Team / Department Head recipients
    private final ComplaintRepository complaintRepository; // audit GAP-023: parent reference in duplicate-review alerts

    /** Gap-backlog Patch 17: optional - absent (e.g. plain unit tests) means synchronous delivery. */
    private Executor notificationExecutor;

    @Autowired(required = false)
    public void setNotificationExecutor(@Qualifier("notificationExecutor") Executor notificationExecutor) {
        this.notificationExecutor = notificationExecutor;
    }

    // ---- Trigger methods (called from complaint/department/escalation services) ----

    /**
     * Citizen-facing alert for a major complaint status change. No-op for
     * any status not in {@link #CITIZEN_NOTIFIABLE_STATUSES} (e.g. the
     * internal SUBMITTED -> AI_PROCESSING transition is not user-facing).
     */
    @Transactional
    public void notifyComplaintStatusChanged(Complaint complaint, ComplaintStatus previous, ComplaintStatus next) {
        if (!CITIZEN_NOTIFIABLE_STATUSES.contains(next) || previous == next) {
            return;
        }
        User citizen = complaint.getCitizen();
        String language = languageOf(citizen);
        String message = NotificationTemplates.render(Event.STATUS_CHANGED, language, Map.of(
                "ref", complaint.getReferenceNumber(),
                "status", NotificationTemplates.statusLabel(next.name(), language)));
        sendOnAllChannels(citizen, complaint, message); // in-app + email mandatory - see NotificationPreferenceKey Javadoc
    }

    /** Officer-facing alert for a new (or reassigned) complaint. */
    @Transactional
    public void notifyOfficerAssigned(Complaint complaint, User officer) {
        if (officer == null) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ref", complaint.getReferenceNumber());
        params.put("category", String.valueOf(complaint.getCategory()));
        params.put("severity", String.valueOf(complaint.getSeverity()));
        sendOnAllChannels(officer, complaint,
                NotificationTemplates.render(Event.OFFICER_ASSIGNED, languageOf(officer), params));
    }

    /** Officer-facing SLA-breach warning at 80% of SLA time elapsed (SRS 15.13). */
    @Transactional
    public void notifySlaBreachWarning(Complaint complaint, User officer) {
        if (officer == null) {
            return;
        }
        sendOnAllChannels(officer, complaint, NotificationTemplates.render(Event.SLA_WARNING, languageOf(officer),
                Map.of("ref", complaint.getReferenceNumber())));
    }

    // ---- Audit GAP-023: remaining SRS 14.1 / 15.13 events ----

    /**
     * SRS 15.13 "escalation triggered": the complaint breached its SLA. Sent to
     * the responsible Department Head(s) and to the assigned officer, if any.
     */
    @Transactional
    public void notifyComplaintEscalated(Complaint complaint, long slaHours) {
        List<User> recipients = new ArrayList<>(departmentHeadsOf(complaint));
        User officer = complaint.getAssignedOfficer();
        if (officer != null && recipients.stream().noneMatch(u -> u.getUserId().equals(officer.getUserId()))) {
            recipients.add(officer);
        }
        for (User recipient : recipients) {
            sendOnAllChannels(recipient, complaint, NotificationTemplates.render(Event.COMPLAINT_ESCALATED,
                    languageOf(recipient), Map.of("ref", complaint.getReferenceNumber(), "hours", Long.toString(slaHours))));
        }
    }

    /**
     * SRS 14.1 step 21: a possible duplicate the AI could not decide (manual
     * review tier) - every active Verification Team member is alerted.
     */
    @Transactional
    public void notifyDuplicateReviewRequired(Complaint complaint, Long candidateParentComplaintId) {
        String parentRef = candidateParentComplaintId == null ? "-"
                : complaintRepository.findById(candidateParentComplaintId)
                        .map(Complaint::getReferenceNumber)
                        .orElse("#" + candidateParentComplaintId);
        for (User reviewer : userRepository.findByRoleAndStatus(Role.VERIFICATION_TEAM, UserStatus.ACTIVE)) {
            sendOnAllChannels(reviewer, complaint, NotificationTemplates.render(Event.DUPLICATE_REVIEW_REQUIRED,
                    languageOf(reviewer), Map.of("ref", complaint.getReferenceNumber(), "parentRef", parentRef)));
        }
    }

    /** SRS 15.7: auto-assignment found no available officer - the Department Head must assign one. */
    @Transactional
    public void notifyNoOfficerAvailable(Complaint complaint) {
        String department = complaint.getDepartment() != null ? complaint.getDepartment().getName() : "-";
        for (User head : departmentHeadsOf(complaint)) {
            sendOnAllChannels(head, complaint, NotificationTemplates.render(Event.NO_OFFICER_AVAILABLE,
                    languageOf(head), Map.of("ref", complaint.getReferenceNumber(), "department", department)));
        }
    }

    /** SRS 15.13 citizen alert: outcome of the citizen's appeal. */
    @Transactional
    public void notifyAppealDecided(Complaint complaint, boolean approved, String reviewNote) {
        User citizen = complaint.getCitizen();
        if (citizen == null) {
            return;
        }
        String language = languageOf(citizen);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ref", complaint.getReferenceNumber());
        params.put("note", reviewNote == null || reviewNote.isBlank() ? "" : " " + reviewNote.trim());
        sendOnAllChannels(citizen, complaint, NotificationTemplates.render(
                approved ? Event.APPEAL_APPROVED : Event.APPEAL_DENIED, language, params));
    }

    /** In-app and email are mandatory (SRS 15.13); SMS and push honour the recipient's opt-out. */
    private void sendOnAllChannels(User recipient, Complaint complaint, String message) {
        dispatch(recipient, complaint, NotificationChannel.IN_APP, message);
        dispatch(recipient, complaint, NotificationChannel.EMAIL, message);
        if (isEnabled(recipient, NotificationPreferenceKey.SMS_ENABLED)) {
            dispatch(recipient, complaint, NotificationChannel.SMS, message);
        }
        dispatchPushIfOptedIn(recipient, complaint, message);
    }

    /** Audit GAP-024: the recipient's personal_language setting (EN default). */
    private String languageOf(User user) {
        return settingRepository.findByScopeAndScopeIdAndKey(SettingScope.USER, user.getUserId(),
                        PersonalSettingKey.LANGUAGE.key())
                .map(Setting::getValue)
                .map(NotificationTemplates::normaliseLanguage)
                .orElse(NotificationTemplates.ENGLISH);
    }

    /** The department's configured head, else every active DEPARTMENT_HEAD user of that department. */
    private List<User> departmentHeadsOf(Complaint complaint) {
        if (complaint.getDepartment() == null) {
            return List.of();
        }
        User head = complaint.getDepartment().getHeadUser();
        if (head != null) {
            return List.of(head);
        }
        return userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(
                Role.DEPARTMENT_HEAD, complaint.getDepartment().getDepartmentId(), UserStatus.ACTIVE);
    }

    // ---- Notification list (SRS 20.5 GET /api/v1/notifications) ----

    @Transactional(readOnly = true)
    public Page<NotificationResponse> listForUser(User user, Pageable pageable) {
        return notificationRepository.findByUser_UserIdOrderByCreatedAtDesc(user.getUserId(), pageable)
                .map(NotificationResponse::from);
    }

    // ---- Preferences (SRS 20.5 GET/PUT /api/v1/notifications/preferences) ----

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getPreferences(User user) {
        return new NotificationPreferencesResponse(
                isEnabled(user, NotificationPreferenceKey.SMS_ENABLED),
                isEnabled(user, NotificationPreferenceKey.PUSH_ENABLED),
                isEnabled(user, NotificationPreferenceKey.EMAIL_ENABLED));
    }

    /** Partial update - a null field leaves that preference unchanged. */
    @Transactional
    public NotificationPreferencesResponse updatePreferences(User user, Boolean smsEnabled, Boolean pushEnabled,
                                                               Boolean emailEnabled) {
        if (smsEnabled != null) {
            upsertPreference(user, NotificationPreferenceKey.SMS_ENABLED, smsEnabled);
        }
        if (pushEnabled != null) {
            upsertPreference(user, NotificationPreferenceKey.PUSH_ENABLED, pushEnabled);
        }
        if (emailEnabled != null) {
            upsertPreference(user, NotificationPreferenceKey.EMAIL_ENABLED, emailEnabled);
        }
        return getPreferences(user);
    }

    /**
     * Gap-backlog Patch 14/16 (Sep 2026 audit): registers (or re-verifies)
     * an FCM device token for push delivery. Upsert by the token's own
     * unique value (V21's uk_device_tokens_token) - the same physical
     * device token re-registering (e.g. every app open) updates
     * lastSeenAt/isActive on the existing row rather than accumulating
     * duplicates. If the token previously belonged to a different user
     * (a shared/reset device, or a stale row from a signed-out account),
     * ownership is reassigned to the current caller - FCM tokens are
     * inherently rebound to whichever account is currently signed in on
     * that device, not permanently tied to the first user who ever
     * registered them.
     */
    @Transactional
    public void registerDeviceToken(User user, String deviceToken, DevicePlatform platform) {
        DeviceToken token = deviceTokenRepository.findByDeviceToken(deviceToken)
                .orElseGet(() -> DeviceToken.builder().deviceToken(deviceToken).build());
        token.setUser(user);
        token.setPlatform(platform);
        token.setIsActive(true);
        token.setLastSeenAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
    }

    @Transactional
    public void deregisterDeviceToken(String deviceToken) {
        deviceTokenRepository.deleteByDeviceToken(deviceToken);
    }

    private void upsertPreference(User user, NotificationPreferenceKey key, boolean value) {
        Setting existing = settingRepository
                .findByScopeAndScopeIdAndKey(SettingScope.USER, user.getUserId(), key.key())
                .orElse(null);
        if (existing != null) {
            existing.setValue(Boolean.toString(value));
            existing.setUpdatedBy(user);
            settingRepository.save(existing);
        } else {
            settingRepository.save(Setting.builder()
                    .scope(SettingScope.USER)
                    .scopeId(user.getUserId())
                    .key(key.key())
                    .value(Boolean.toString(value))
                    .updatedBy(user)
                    .build());
        }
    }

    /** @return the stored preference, or {@code true} (opted in) if the user has never set it. */
    /**
     * Remaining-gaps item 4: the PUSH preference was stored and returned by
     * GET/PUT /notifications/preferences but never consulted - no trigger
     * sent PUSH at all, so the toggle had no effect. SRS 15.13: citizens may
     * opt out of SMS/push; in-app and email stay mandatory (see
     * NotificationPreferenceKey), so only SMS and PUSH are gated.
     *
     * PUSH is attempted only when push delivery is actually configured
     * (enabled + FCM credentials path, the same condition PushGatewayClient
     * uses); otherwise no PUSH row is written, so a disabled channel is never
     * recorded as DELIVERED.
     */
    private void dispatchPushIfOptedIn(User recipient, Complaint complaint, String message) {
        if (!pushDeliveryConfigured()) {
            return;
        }
        if (isEnabled(recipient, NotificationPreferenceKey.PUSH_ENABLED)) {
            dispatch(recipient, complaint, NotificationChannel.PUSH, message);
        }
    }

    private boolean pushDeliveryConfigured() {
        NotificationProperties.Push push = properties.getPush();
        return push.isEnabled() && push.getCredentialsPath() != null && !push.getCredentialsPath().isBlank();
    }

    private boolean isEnabled(User user, NotificationPreferenceKey key) {
        return settingRepository.findByScopeAndScopeIdAndKey(SettingScope.USER, user.getUserId(), key.key())
                .map(Setting::getValue)
                .map(Boolean::parseBoolean)
                .orElse(true);
    }

    // ---- Dispatch with retry (SRS 15.13 Validation Rules: retry up to 3 times) ----

    /**
     * Gap-backlog Patch 17: everything a delivery attempt needs, read from the
     * entities while still inside the caller's persistence context, so a
     * worker thread never touches a lazy proxy after the session closes.
     */
    private record DeliveryTarget(Long userId, String email, String mobileNumber, String referenceNumber) {
    }

    private void dispatch(User recipient, Complaint complaint, NotificationChannel channel, String message) {
        DeliveryTarget target = new DeliveryTarget(recipient.getUserId(), recipient.getEmail(),
                recipient.getMobileNumber(), complaint.getReferenceNumber());
        Executor executor = this.notificationExecutor;
        if (channel == NotificationChannel.IN_APP || !properties.isAsyncEnabled() || executor == null) {
            deliver(recipient, complaint, channel, message, target);
            return;
        }
        Runnable task = () -> {
            try {
                deliver(recipient, complaint, channel, message, target);
            } catch (RuntimeException e) {
                log.error("Async notification delivery crashed for user {} via {}: {}",
                        target.userId(), channel, e.getMessage(), e);
            }
        };
        // Only hand off once the caller's transaction has committed: before
        // that, the complaint/status change the message describes may still
        // roll back, and the worker's own row insert could race the commit.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(task);
                }
            });
        } else {
            executor.execute(task);
        }
    }

    private void deliver(User recipient, Complaint complaint, NotificationChannel channel, String message,
                         DeliveryTarget target) {
        int maxAttempts = properties.getMaxDeliveryAttempts();
        int attempts = 0;
        DeliveryStatus status = DeliveryStatus.FAILED;
        while (attempts < maxAttempts) {
            attempts++;
            try {
                DeliveryOutcome outcome = sendOnce(target, channel, message);
                if (outcome == DeliveryOutcome.SKIPPED) {
                    // Audit GAP-022: nothing was transmitted - never recorded as DELIVERED.
                    status = DeliveryStatus.SKIPPED;
                    attempts = 0;
                } else {
                    status = DeliveryStatus.DELIVERED;
                }
                break;
            } catch (NotificationDeliveryException e) {
                log.warn("Notification delivery attempt {}/{} failed for user {} via {}: {}",
                        attempts, maxAttempts, target.userId(), channel, e.getMessage());
                if (attempts < maxAttempts) {
                    sleepBackoff(attempts);
                }
            }
        }
        notificationRepository.save(Notification.builder()
                .user(recipient)
                .complaint(complaint)
                .channel(channel)
                .message(message.length() > 500 ? message.substring(0, 500) : message)
                .deliveryStatus(status)
                .deliveryAttempts(attempts)
                .build());
    }

    private DeliveryOutcome sendOnce(DeliveryTarget target, NotificationChannel channel, String message) {
        return switch (channel) {
            // Persisting the row itself (in deliver()) is the in-app delivery.
            case IN_APP -> DeliveryOutcome.SENT;
            case EMAIL -> emailGatewayClient.send(target.email(), "JanNet AI - " + target.referenceNumber(), message);
            case SMS -> smsGatewayClient.send(target.mobileNumber(), message, SmsMessageType.NOTIFICATION);
            case PUSH -> pushGatewayClient.send(target.userId(), "JanNet AI - " + target.referenceNumber(), message);
        };
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(properties.getRetryBackoffBaseMs() * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

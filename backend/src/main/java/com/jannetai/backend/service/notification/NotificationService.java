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
import com.jannetai.backend.entity.enums.SettingScope;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.NotificationRepository;
import com.jannetai.backend.repository.SettingRepository;
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
import java.util.EnumSet;
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
            ComplaintStatus.REJECTED);

    private final NotificationRepository notificationRepository;
    private final SettingRepository settingRepository;
    private final NotificationProperties properties;
    private final EmailGatewayClient emailGatewayClient;
    private final SmsGatewayClient smsGatewayClient;
    private final PushGatewayClient pushGatewayClient;
    private final DeviceTokenRepository deviceTokenRepository;

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
        String message = "Your complaint " + complaint.getReferenceNumber() + " is now " + statusLabel(next) + ".";
        dispatch(citizen, complaint, NotificationChannel.IN_APP, message);
        dispatch(citizen, complaint, NotificationChannel.EMAIL, message); // mandatory - see NotificationPreferenceKey Javadoc
        if (isEnabled(citizen, NotificationPreferenceKey.SMS_ENABLED)) {
            dispatch(citizen, complaint, NotificationChannel.SMS, message);
        }
    }

    /** Officer-facing alert for a new (or reassigned) complaint. */
    @Transactional
    public void notifyOfficerAssigned(Complaint complaint, User officer) {
        if (officer == null) {
            return;
        }
        String message = "New complaint assigned to you: " + complaint.getReferenceNumber()
                + " (" + complaint.getCategory() + ", " + complaint.getSeverity() + " severity).";
        dispatch(officer, complaint, NotificationChannel.IN_APP, message);
        dispatch(officer, complaint, NotificationChannel.EMAIL, message);
        if (isEnabled(officer, NotificationPreferenceKey.SMS_ENABLED)) {
            dispatch(officer, complaint, NotificationChannel.SMS, message);
        }
    }

    /** Officer-facing SLA-breach warning at 80% of SLA time elapsed (SRS 15.13). */
    @Transactional
    public void notifySlaBreachWarning(Complaint complaint, User officer) {
        if (officer == null) {
            return;
        }
        String message = "SLA warning: complaint " + complaint.getReferenceNumber()
                + " is approaching its SLA deadline. Please review it soon.";
        dispatch(officer, complaint, NotificationChannel.IN_APP, message);
        dispatch(officer, complaint, NotificationChannel.EMAIL, message);
        if (isEnabled(officer, NotificationPreferenceKey.SMS_ENABLED)) {
            dispatch(officer, complaint, NotificationChannel.SMS, message);
        }
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
                sendOnce(target, channel, message);
                status = DeliveryStatus.DELIVERED;
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

    private void sendOnce(DeliveryTarget target, NotificationChannel channel, String message) {
        switch (channel) {
            case IN_APP -> { /* persisting the row itself (in deliver()) is the delivery - nothing more to do. */ }
            case EMAIL -> emailGatewayClient.send(target.email(), "JanNet AI - " + target.referenceNumber(), message);
            case SMS -> smsGatewayClient.send(target.mobileNumber(), message);
            case PUSH -> pushGatewayClient.send(target.userId(), "JanNet AI - " + target.referenceNumber(), message);
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(properties.getRetryBackoffBaseMs() * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String statusLabel(ComplaintStatus status) {
        return switch (status) {
            case SUBMITTED -> "submitted";
            case VERIFIED -> "verified";
            case ASSIGNED -> "assigned to an officer";
            case IN_PROGRESS -> "in progress";
            case RESOLVED -> "resolved";
            case REJECTED -> "rejected";
            default -> status.name();
        };
    }
}

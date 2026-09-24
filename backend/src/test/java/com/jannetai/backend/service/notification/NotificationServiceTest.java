package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Notification;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import com.jannetai.backend.entity.enums.NotificationChannel;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.NotificationRepository;
import com.jannetai.backend.repository.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationService}'s dispatch-with-retry logic
 * (SRS 15.13 Validation Rules: "retry up to 3 times") and the citizen
 * notifiable-status filter. {@code retryBackoffBaseMs} is set to 0 so
 * these tests don't actually sleep through the exponential backoff.
 *
 * NOT EXECUTED in this workspace (no Maven Central reach - see
 * PROJECT_PROGRESS.md's Phase 20 TESTS section). Manually validated
 * against NotificationService.java's actual dispatch/sendOnce/
 * CITIZEN_NOTIFIABLE_STATUSES logic.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationRepository notificationRepository;
    @Mock private SettingRepository settingRepository;
    @Mock private EmailGatewayClient emailGatewayClient;
    @Mock private SmsGatewayClient smsGatewayClient;
    @Mock private PushGatewayClient pushGatewayClient;
    @Mock private DeviceTokenRepository deviceTokenRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxDeliveryAttempts(3);
        properties.setRetryBackoffBaseMs(0);
        notificationService = new NotificationService(
                notificationRepository, settingRepository, properties, emailGatewayClient, smsGatewayClient,
                pushGatewayClient, deviceTokenRepository);
        // No stored preference for any user in these tests -> isEnabled()
        // defaults to true (opted in), matching NotificationService's own
        // documented "never set = opted in" default.
        when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
    }

    private User user(long id, String email, String mobile) {
        return User.builder().userId(id).role(Role.CITIZEN).email(email).mobileNumber(mobile)
                .fullName("User " + id).build();
    }

    private Complaint complaint(User citizen) {
        return Complaint.builder().complaintId(1L).referenceNumber("JN-2026-000001")
                .citizen(citizen).status(ComplaintStatus.VERIFIED).build();
    }

    @Test
    void emailDeliveredOnFirstAttemptRecordsDeliveredWithOneAttempt() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture()); // IN_APP + EMAIL + SMS (default opt-in)
        boolean anyEmailDelivered = captor.getAllValues().stream()
                .anyMatch(n -> n.getChannel() == NotificationChannel.EMAIL
                        && n.getDeliveryStatus() == DeliveryStatus.DELIVERED
                        && n.getDeliveryAttempts() == 1);
        assertThat(anyEmailDelivered).isTrue();
    }

    @Test
    void emailRetriesUpToMaxAttemptsThenRecordsFailed() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);
        doThrow(new NotificationDeliveryException("SMTP down"))
                .when(emailGatewayClient).send(any(), any(), any());

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);

        verify(emailGatewayClient, times(3)).send(any(), any(), any()); // maxDeliveryAttempts = 3
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        boolean emailFailedAfterThreeAttempts = captor.getAllValues().stream()
                .anyMatch(n -> n.getChannel() == NotificationChannel.EMAIL
                        && n.getDeliveryStatus() == DeliveryStatus.FAILED
                        && n.getDeliveryAttempts() == 3);
        assertThat(emailFailedAfterThreeAttempts).isTrue();
    }

    @Test
    void succeedsOnSecondAttemptAfterOneTransientFailure() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);
        doThrow(new NotificationDeliveryException("transient"))
                .doNothing()
                .when(emailGatewayClient).send(any(), any(), any());

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);

        verify(emailGatewayClient, times(2)).send(any(), any(), any());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        boolean emailDeliveredOnSecondAttempt = captor.getAllValues().stream()
                .anyMatch(n -> n.getChannel() == NotificationChannel.EMAIL
                        && n.getDeliveryStatus() == DeliveryStatus.DELIVERED
                        && n.getDeliveryAttempts() == 2);
        assertThat(emailDeliveredOnSecondAttempt).isTrue();
    }

    @Test
    void nonNotifiableStatusTransitionSendsNothing() {
        // SUBMITTED -> AI_PROCESSING is not in CITIZEN_NOTIFIABLE_STATUSES.
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.SUBMITTED, ComplaintStatus.AI_PROCESSING);

        verify(notificationRepository, never()).save(any());
        verify(emailGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void noOpTransitionWherePreviousEqualsNextSendsNothing() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.VERIFIED, ComplaintStatus.VERIFIED);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void officerAssignedNotificationIsSkippedWhenOfficerIsNull() {
        // reassign() calls this even for a department-level (no-officer)
        // reassignment - must be a safe no-op, not an NPE.
        Complaint c = complaint(user(1L, "citizen@example.com", "+911111111111"));

        notificationService.notifyOfficerAssigned(c, null);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void smsIsSkippedWhenUserHasOptedOut() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        Complaint c = complaint(citizen);
        when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(), org.mockito.ArgumentMatchers.eq("notification_sms_enabled")))
                .thenReturn(java.util.Optional.of(
                        com.jannetai.backend.entity.Setting.builder().value("false").build()));

        notificationService.notifyComplaintStatusChanged(c, ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);

        verify(smsGatewayClient, never()).send(any(), any());
        // IN_APP + EMAIL still sent (only SMS is preference-gated).
        verify(notificationRepository, times(2)).save(any());
    }

    // ---- Remaining-gaps item 4: push preference enforcement ----

    private NotificationService serviceWithPushConfigured() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxDeliveryAttempts(3);
        properties.setRetryBackoffBaseMs(0);
        properties.getPush().setEnabled(true);
        properties.getPush().setCredentialsPath("/run/secrets/fcm.json");
        return new NotificationService(
                notificationRepository, settingRepository, properties, emailGatewayClient, smsGatewayClient,
                pushGatewayClient, deviceTokenRepository);
    }

    private void storedPreference(NotificationPreferenceKey key, String value) {
        when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(),
                org.mockito.ArgumentMatchers.eq(key.key())))
                .thenReturn(java.util.Optional.of(
                        com.jannetai.backend.entity.Setting.builder().key(key.key()).value(value).build()));
    }

    @Test
    void pushIsNotAttemptedWhenPushDeliveryIsNotConfigured() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(pushGatewayClient, never()).send(any(), any(), any());
        verify(notificationRepository, times(3)).save(any());
    }

    @Test
    void pushIsSentWhenConfiguredAndUserHasNotOptedOut() {
        NotificationService service = serviceWithPushConfigured();
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        service.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(pushGatewayClient, times(1)).send(any(), any(), any());
        verify(notificationRepository, times(4)).save(any()); // IN_APP + EMAIL + SMS + PUSH
    }

    @Test
    void pushIsSuppressedWhenUserOptedOutOfPush() {
        NotificationService service = serviceWithPushConfigured();
        storedPreference(NotificationPreferenceKey.PUSH_ENABLED, "false");
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        service.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(pushGatewayClient, never()).send(any(), any(), any());
        verify(smsGatewayClient, times(1)).send(any(), any());
    }

    @Test
    void smsIsSuppressedWhenUserOptedOutOfSms() {
        storedPreference(NotificationPreferenceKey.SMS_ENABLED, "false");
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(smsGatewayClient, never()).send(any(), any());
    }

    @Test
    void emailStaysMandatoryForStatusChangesEvenIfEmailPreferenceIsOff() {
        // SRS 15.13 Exceptions: in-app and email notifications are mandatory.
        storedPreference(NotificationPreferenceKey.EMAIL_ENABLED, "false");
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(emailGatewayClient, times(1)).send(any(), any(), any());
    }

    @Test
    void officerAlertsHonourPushOptOutToo() {
        NotificationService service = serviceWithPushConfigured();
        storedPreference(NotificationPreferenceKey.PUSH_ENABLED, "false");
        User officer = user(2L, "officer@example.com", "+912222222222");
        service.notifyOfficerAssigned(complaint(user(1L, "c@example.com", "+911111111111")), officer);
        verify(pushGatewayClient, never()).send(any(), any(), any());
    }
}

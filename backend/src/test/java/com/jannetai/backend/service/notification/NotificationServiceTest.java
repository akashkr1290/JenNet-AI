package com.jannetai.backend.service.notification;

import com.jannetai.backend.config.NotificationProperties;
import com.jannetai.backend.entity.Complaint;
import com.jannetai.backend.entity.Notification;
import com.jannetai.backend.entity.User;
import com.jannetai.backend.entity.enums.ComplaintStatus;
import com.jannetai.backend.entity.enums.DeliveryStatus;
import com.jannetai.backend.entity.enums.NotificationChannel;
import com.jannetai.backend.entity.enums.Role;
import com.jannetai.backend.entity.Department;
import com.jannetai.backend.entity.Setting;
import com.jannetai.backend.entity.enums.UserStatus;
import com.jannetai.backend.repository.ComplaintRepository;
import com.jannetai.backend.repository.DeviceTokenRepository;
import com.jannetai.backend.repository.NotificationRepository;
import com.jannetai.backend.repository.SettingRepository;
import com.jannetai.backend.repository.UserRepository;
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
import static org.mockito.Mockito.lenient;
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
    @Mock private UserRepository userRepository;
    @Mock private ComplaintRepository complaintRepository;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        NotificationProperties properties = new NotificationProperties();
        properties.setMaxDeliveryAttempts(3);
        properties.setRetryBackoffBaseMs(0);
        notificationService = new NotificationService(
                notificationRepository, settingRepository, properties, emailGatewayClient, smsGatewayClient,
                pushGatewayClient, deviceTokenRepository, userRepository, complaintRepository);
        // No stored preference for any user in these tests -> isEnabled()
        // defaults to true (opted in), matching NotificationService's own
        // documented "never set = opted in" default.
        // lenient: some tests (e.g. a null officer) never look a preference up.
        lenient().when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(), anyString()))
                .thenReturn(java.util.Optional.empty());
        // Audit GAP-022: gateways now report what they did; by default each one transmits.
        lenient().when(emailGatewayClient.send(any(), any(), any())).thenReturn(DeliveryOutcome.SENT);
        lenient().when(smsGatewayClient.send(any(), any(), any())).thenReturn(DeliveryOutcome.SENT);
        lenient().when(pushGatewayClient.send(any(), any(), any())).thenReturn(DeliveryOutcome.SENT);
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
                .doReturn(DeliveryOutcome.SENT)
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

        verify(smsGatewayClient, never()).send(any(), any(), any());
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
                pushGatewayClient, deviceTokenRepository, userRepository, complaintRepository);
    }

    private void storedPreference(NotificationPreferenceKey key, String value) {
        when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(),
                org.mockito.ArgumentMatchers.eq(key.key())))
                .thenReturn(java.util.Optional.of(
                        com.jannetai.backend.entity.Setting.builder().key(key.key()).value(value).build()));
    }

    /**
     * Same as {@link #storedPreference}, but lenient - for EMAIL_ENABLED
     * specifically, which the mandatory-email path (SRS 15.13 Exceptions)
     * never looks up. Kept lenient rather than dropped, as documentation
     * that "off" was actually attempted and still didn't stop the email.
     */
    private void lenientStoredPreference(NotificationPreferenceKey key, String value) {
        lenient().when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(),
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
        verify(smsGatewayClient, times(1)).send(any(), any(), any());
    }

    @Test
    void smsIsSuppressedWhenUserOptedOutOfSms() {
        storedPreference(NotificationPreferenceKey.SMS_ENABLED, "false");
        User citizen = user(1L, "citizen@example.com", "+911111111111");
        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);
        verify(smsGatewayClient, never()).send(any(), any(), any());
    }

    @Test
    void emailStaysMandatoryForStatusChangesEvenIfEmailPreferenceIsOff() {
        // SRS 15.13 Exceptions: in-app and email notifications are mandatory.
        lenientStoredPreference(NotificationPreferenceKey.EMAIL_ENABLED, "false");
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

    // ---- Audit GAP-022: SKIPPED instead of DELIVERED when nothing was transmitted ----

    @Test
    void disabledEmailChannelIsRecordedAsSkippedNotDelivered() {
        when(emailGatewayClient.send(any(), any(), any())).thenReturn(DeliveryOutcome.SKIPPED);
        User citizen = user(1L, null, "+911111111111");

        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.VERIFIED);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        Notification email = captor.getAllValues().stream()
                .filter(n -> n.getChannel() == NotificationChannel.EMAIL).findFirst().orElseThrow();
        assertThat(email.getDeliveryStatus()).isEqualTo(DeliveryStatus.SKIPPED);
        assertThat(email.getDeliveryAttempts()).isZero();
        verify(emailGatewayClient, times(1)).send(any(), any(), any()); // a skip is not retried
        Notification inApp = captor.getAllValues().stream()
                .filter(n -> n.getChannel() == NotificationChannel.IN_APP).findFirst().orElseThrow();
        assertThat(inApp.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    // ---- Audit GAP-024: localized templates ----

    @Test
    void hindiRecipientGetsTheHindiTemplate() {
        when(settingRepository.findByScopeAndScopeIdAndKey(any(), any(), org.mockito.ArgumentMatchers.eq("personal_language")))
                .thenReturn(java.util.Optional.of(Setting.builder().key("personal_language").value("HI").build()));
        User citizen = user(1L, "citizen@example.com", "+911111111111");

        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage())
                .isEqualTo("आपकी शिकायत JN-2026-000001 की स्थिति: समाधान हो गया।");
    }

    @Test
    void englishIsTheDefaultAndKeepsTheOriginalWording() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");

        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage()).isEqualTo("Your complaint JN-2026-000001 is now resolved.");
    }

    // ---- Audit GAP-023: remaining events ----

    @Test
    void closureAndDuplicateMergeNowNotifyTheCitizen() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");

        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.RESOLVED, ComplaintStatus.CLOSED);
        notificationService.notifyComplaintStatusChanged(complaint(citizen), ComplaintStatus.AI_PROCESSING, ComplaintStatus.DUPLICATE);

        verify(notificationRepository, times(6)).save(any());
    }

    @Test
    void escalationAlertsTheDepartmentHeadAndTheAssignedOfficer() {
        User head = user(5L, "head@example.com", "+915555555555");
        User officer = user(6L, "officer@example.com", "+916666666666");
        Complaint c = complaint(user(1L, "citizen@example.com", "+911111111111"));
        c.setDepartment(Department.builder().departmentId(3L).name("Roads").headUser(head).build());
        c.setAssignedOfficer(officer);

        notificationService.notifyComplaintEscalated(c, 72);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(6)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(n -> n.getUser().getUserId()).containsOnly(5L, 6L);
        assertThat(captor.getAllValues().get(0).getMessage()).contains("JN-2026-000001").contains("72-hour");
    }

    @Test
    void possibleDuplicateAlertsEveryActiveVerificationTeamMember() {
        User reviewer = user(9L, "vt@example.com", "+919999999999");
        when(userRepository.findByRoleAndStatus(Role.VERIFICATION_TEAM, UserStatus.ACTIVE)).thenReturn(java.util.List.of(reviewer));
        when(complaintRepository.findById(44L)).thenReturn(java.util.Optional.of(
                Complaint.builder().complaintId(44L).referenceNumber("JN-2026-000044").build()));

        notificationService.notifyDuplicateReviewRequired(complaint(user(1L, "c@example.com", "+911111111111")), 44L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getUser().getUserId()).isEqualTo(9L);
        assertThat(captor.getAllValues().get(0).getMessage()).contains("JN-2026-000044");
    }

    @Test
    void noOfficerAvailableFallsBackToTheDepartmentsHeadUsers() {
        User head = user(5L, "head@example.com", "+915555555555");
        Complaint c = complaint(user(1L, "c@example.com", "+911111111111"));
        c.setDepartment(Department.builder().departmentId(3L).name("Roads").build()); // no head_user_id configured
        when(userRepository.findByRoleAndDepartment_DepartmentIdAndStatus(Role.DEPARTMENT_HEAD, 3L, UserStatus.ACTIVE))
                .thenReturn(java.util.List.of(head));

        notificationService.notifyNoOfficerAvailable(c);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage()).contains("Roads").contains("no officer");
    }

    @Test
    void appealOutcomeIsSentToTheCitizen() {
        User citizen = user(1L, "citizen@example.com", "+911111111111");

        notificationService.notifyAppealDecided(complaint(citizen), false, "Photo does not show the reported issue.");

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage())
                .isEqualTo("Your appeal for complaint JN-2026-000001 was not approved. Photo does not show the reported issue.");
    }
}

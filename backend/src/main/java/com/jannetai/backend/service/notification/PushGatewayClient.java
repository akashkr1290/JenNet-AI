package com.jannetai.backend.service.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.jannetai.backend.config.NotificationProperties;
import com.jannetai.backend.entity.DeviceToken;
import com.jannetai.backend.repository.DeviceTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Gap-backlog Patch 14/16 (Sep 2026 audit): real push delivery via
 * Firebase Cloud Messaging - the piece NotificationService's own Javadoc
 * named as the reason PUSH was never actually dispatched. Same "off by
 * default, logs a stub instead" convention as
 * {@link SmsGatewayClient}/{@link EmailGatewayClient} (see
 * {@link NotificationProperties.Push}'s Javadoc), gated on
 * {@code app.notification.push.enabled} plus a real credentials file
 * actually being present at {@code app.notification.push.credentials-path}.
 *
 * One push per active {@link DeviceToken} the recipient has registered
 * (a user may have more than one device - phone + tablet, or having
 * reinstalled the app) - a token FCM reports as no-longer-valid
 * ({@code UNREGISTERED}) is deactivated here rather than left to fail
 * silently on every future send.
 */
@Component
public class PushGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(PushGatewayClient.class);

    private final NotificationProperties properties;
    private final DeviceTokenRepository deviceTokenRepository;
    private volatile boolean firebaseInitAttempted = false;
    private volatile boolean firebaseAvailable = false;

    public PushGatewayClient(NotificationProperties properties, DeviceTokenRepository deviceTokenRepository) {
        this.properties = properties;
        this.deviceTokenRepository = deviceTokenRepository;
    }

    /**
     * @throws NotificationDeliveryException only on a genuine delivery
     *         failure with at least one active token - never thrown
     *         merely because push is disabled or the recipient has no
     *         registered device (both are normal, silent no-ops, same as
     *         SmsGatewayClient/EmailGatewayClient's disabled-channel stub).
     */
    public void send(Long userId, String title, String message) {
        NotificationProperties.Push push = properties.getPush();
        if (!push.isEnabled() || push.getCredentialsPath() == null || push.getCredentialsPath().isBlank()) {
            log.warn("[PUSH-STUB] Would send push to user {}: {} - app.notification.push.enabled is false "
                    + "or no credentials path is configured.", userId, message);
            return;
        }

        List<DeviceToken> tokens = deviceTokenRepository.findByUser_UserIdAndIsActiveTrue(userId);
        if (tokens.isEmpty()) {
            log.debug("No active device tokens registered for user {} - push skipped.", userId);
            return;
        }

        if (!ensureFirebaseInitialized(push.getCredentialsPath())) {
            throw new NotificationDeliveryException(
                    "Firebase could not be initialized from " + push.getCredentialsPath() + " - see earlier log for the cause");
        }

        for (DeviceToken deviceToken : tokens) {
            try {
                Message fcmMessage = Message.builder()
                        .setToken(deviceToken.getDeviceToken())
                        .setNotification(Notification.builder()
                                .setTitle(title)
                                .setBody(message)
                                .build())
                        .build();
                FirebaseMessaging.getInstance().send(fcmMessage);
            } catch (FirebaseMessagingException e) {
                if ("UNREGISTERED".equals(e.getMessagingErrorCode() != null ? e.getMessagingErrorCode().name() : null)) {
                    // The device uninstalled the app or the token otherwise
                    // expired - FCM itself is telling us this token is
                    // permanently dead, so stop trying it on every future
                    // send rather than accumulating delivery failures.
                    deviceToken.setIsActive(false);
                    deviceTokenRepository.save(deviceToken);
                    log.info("Device token for user {} is no longer registered with FCM - deactivated.", userId);
                    continue;
                }
                throw new NotificationDeliveryException(
                        "FCM send failed for user " + userId + ": " + e.getMessage(), e);
            }
        }
    }

    private synchronized boolean ensureFirebaseInitialized(String credentialsPath) {
        if (firebaseInitAttempted) {
            return firebaseAvailable;
        }
        firebaseInitAttempted = true;
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                try (FileInputStream serviceAccount = new FileInputStream(credentialsPath)) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                            .build();
                    FirebaseApp.initializeApp(options);
                }
            }
            firebaseAvailable = true;
        } catch (IOException e) {
            log.error("Failed to initialize Firebase from credentials at {}: {}", credentialsPath, e.getMessage());
            firebaseAvailable = false;
        }
        return firebaseAvailable;
    }
}

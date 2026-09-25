package com.jannetai.backend.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-056: max delivery attempts can never violate notifications.delivery_attempts' CHECK (0-3). */
class NotificationPropertiesTest {

    @Test
    void attemptsAreClampedToTheDatabaseRange() {
        NotificationProperties p = new NotificationProperties();
        p.setMaxDeliveryAttempts(5);
        assertThat(p.getMaxDeliveryAttempts()).isEqualTo(3);
        p.setMaxDeliveryAttempts(0);
        assertThat(p.getMaxDeliveryAttempts()).isEqualTo(1);
        p.setMaxDeliveryAttempts(2);
        assertThat(p.getMaxDeliveryAttempts()).isEqualTo(2);
    }
}

package com.jannetai.backend.service.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Remaining-gaps item 15: contact identifiers never appear in full in logs. */
class PiiMaskTest {

    @Test
    void emailKeepsFirstCharacterAndDomainOnly() {
        assertEquals("c***@example.com", PiiMask.email("citizen.name@example.com"));
        assertTrue(!PiiMask.email("citizen.name@example.com").contains("citizen.name"));
    }

    @Test
    void phoneKeepsOnlyLastTwoDigits() {
        assertEquals("**********10", PiiMask.phone("+91 98765 43210"));
        assertTrue(!PiiMask.phone("9876543210").contains("98765"));
    }

    @Test
    void nullBlankAndMalformedValuesAreSafe() {
        assertEquals("(none)", PiiMask.email(null));
        assertEquals("(none)", PiiMask.email(" "));
        assertEquals("***", PiiMask.email("no-at-sign"));
        assertEquals("(none)", PiiMask.phone(null));
        assertEquals("***", PiiMask.phone("7"));
    }
}

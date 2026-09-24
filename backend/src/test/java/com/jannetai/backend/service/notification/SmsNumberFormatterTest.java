package com.jannetai.backend.service.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Registration OTP fix: numbers reach the SMS provider in international form and are never double-prefixed. */
class SmsNumberFormatterTest {

    private static final String IN = "+91";

    @Test
    void storedTenDigitIndianNumberGetsTheCountryCode() {
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("9876543210", IN));
    }

    @Test
    void numberAlreadyInInternationalFormIsNotPrefixedAgain() {
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("+919876543210", IN));
        assertEquals("+14155550123", SmsNumberFormatter.toInternational("+14155550123", IN));
    }

    @Test
    void spacesDashesAndBracketsAreRemoved() {
        assertEquals("+919876543210", SmsNumberFormatter.toInternational(" +91 98765-43210 ", IN));
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("(98765) 43210", IN));
    }

    @Test
    void countryCodeWithoutPlusTrunkZeroAndDoubleZeroPrefixAreNormalised() {
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("919876543210", IN));
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("09876543210", IN));
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("00919876543210", IN));
    }

    @Test
    void countryCodeSettingMayBeGivenWithOrWithoutPlus() {
        assertEquals("+919876543210", SmsNumberFormatter.toInternational("9876543210", "91"));
    }

    @Test
    void invalidNumbersAreRejectedNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational(null, IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("  ", IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("98765abcde", IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("12345", IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("+0123456789", IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("+1234567890123456", IN));
        assertThrows(IllegalArgumentException.class, () -> SmsNumberFormatter.toInternational("9876543210", ""));
    }
}

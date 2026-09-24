package com.jannetai.backend.service.notification;

/**
 * Registration OTP fix: formats a stored mobile number for the SMS provider.
 *
 * Registration accepts and stores Indian mobile numbers as 10 national digits
 * ({@code ^[6-9]\d{9}$}, RegisterRequest), and that bare number used to be sent
 * to the SMS provider as-is. SMS APIs expect an international (E.164) number,
 * so a bare national number is rejected or misrouted. This formats the number
 * at the provider boundary only - validation and storage are unchanged.
 *
 * Rules (a number that already carries a country code is never prefixed again):
 * <pre>
 *   "+919876543210", "+91 98765-43210" -> "+919876543210"  already international
 *   "00919876543210"                   -> "+919876543210"  international access prefix
 *   "919876543210"                     -> "+919876543210"  country code without "+"
 *   "09876543210"                      -> "+919876543210"  national trunk prefix 0
 *   "9876543210"                       -> "+919876543210"  10-digit national number
 * </pre>
 * The national-number length (10) matches India, this project's default
 * country (+91). Anything else is rejected rather than guessed.
 */
public final class SmsNumberFormatter {

    private static final int NATIONAL_NUMBER_LENGTH = 10;

    private SmsNumberFormatter() {
    }

    /**
     * @param number             the stored or entered mobile number
     * @param defaultCountryCode e.g. "+91" or "91" - applied only to national numbers
     * @return the number in E.164 form, e.g. "+919876543210"
     * @throws IllegalArgumentException if the number cannot be formatted unambiguously
     */
    public static String toInternational(String number, String defaultCountryCode) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("mobile number is required");
        }
        String countryCode = defaultCountryCode == null ? "" : defaultCountryCode.replaceAll("[^0-9]", "");
        if (countryCode.isEmpty()) {
            throw new IllegalArgumentException("default country code is not configured");
        }
        String trimmed = number.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits = (hasPlus ? trimmed.substring(1) : trimmed).replaceAll("[\\s\\-().]", "");
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("mobile number contains invalid characters");
        }
        if (hasPlus) {
            return international(digits);
        }
        if (digits.startsWith("00")) {
            return international(digits.substring(2));
        }
        if (digits.length() == NATIONAL_NUMBER_LENGTH && digits.charAt(0) != '0') {
            return "+" + countryCode + digits;
        }
        if (digits.length() == NATIONAL_NUMBER_LENGTH + 1 && digits.charAt(0) == '0') {
            return "+" + countryCode + digits.substring(1);
        }
        if (digits.length() == countryCode.length() + NATIONAL_NUMBER_LENGTH && digits.startsWith(countryCode)) {
            return "+" + digits;
        }
        throw new IllegalArgumentException("mobile number is not a valid national or international number");
    }

    private static String international(String digits) {
        // E.164: at most 15 digits including the country code; a country code never starts with 0.
        if (digits.length() < 8 || digits.length() > 15 || digits.charAt(0) == '0') {
            throw new IllegalArgumentException("mobile number is not a valid international number");
        }
        return "+" + digits;
    }
}

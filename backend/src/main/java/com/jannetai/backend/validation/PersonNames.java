package com.jannetai.backend.validation;

/**
 * Audit GAP-054 (SRS 17.1, users.full_name: "Alpha + spaces, 2-100 chars").
 *
 * "Alpha" is read as any Unicode letter, so Devanagari and other Indian
 * scripts are accepted (a Hindi name such as "राम कुमार" contains combining
 * vowel signs, which are Unicode marks, so marks that follow a letter are
 * accepted too). Words are separated by single spaces; leading/trailing
 * spaces, digits, and punctuation (including '.', '-' and apostrophes) are
 * rejected because the SRS lists letters and spaces only. Whether initials
 * with a full stop ("A. Kumar") or hyphenated/apostrophe names should be
 * allowed is a product-owner decision recorded in docs/SRS_PHASE06_DECISIONS.md.
 *
 * Pure JDK so it can be unit-tested without Spring.
 */
public final class PersonNames {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;
    public static final String MESSAGE =
            "must be 2-100 characters and contain only letters and single spaces between words";

    private PersonNames() {
    }

    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        int length = name.codePointCount(0, name.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        boolean previousWasSpace = true; // forbids a leading space and a leading mark
        int i = 0;
        while (i < name.length()) {
            int cp = name.codePointAt(i);
            if (cp == ' ') {
                if (previousWasSpace) {
                    return false; // leading or double space
                }
                previousWasSpace = true;
            } else if (Character.isLetter(cp)) {
                previousWasSpace = false;
            } else if (isMark(cp)) {
                if (previousWasSpace) {
                    return false; // a mark must follow a letter
                }
            } else {
                return false;
            }
            i += Character.charCount(cp);
        }
        return !previousWasSpace; // forbids a trailing space
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}

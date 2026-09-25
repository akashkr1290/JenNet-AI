package com.jannetai.backend.service.complaint;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Audit GAP-054 (SRS 17.2, complaints.description: "Max 500 characters,
 * profanity filter"): whole-word, case-insensitive matching of a configured
 * word/phrase list against free text.
 *
 * <ul>
 *   <li>Text is split into words: maximal runs of Unicode letters, digits and
 *       combining marks, so Devanagari words are matched as whole words and
 *       "class" never matches an entry "ass".</li>
 *   <li>Each word is compared after NFKC normalisation and lower-casing
 *       (full-width and compatibility forms fold to the same entry).</li>
 *   <li>An entry of several words ("some phrase") matches that exact word
 *       sequence.</li>
 * </ul>
 * Deliberately no leetspeak/obfuscation guessing: the SRS does not ask for it
 * and it produces false positives on civic text. The word list itself is NOT
 * shipped - it is a product-owner decision (see docs/SRS_PHASE06_DECISIONS.md).
 *
 * Pure JDK so it can be unit-tested without Spring.
 */
public final class ProfanityMatcher {

    /** One word of the input and its position in the original string. */
    record Token(String normalized, int start, int end) {
    }

    private final Set<List<String>> entries;

    public ProfanityMatcher(Collection<String> words) {
        Set<List<String>> parsed = new LinkedHashSet<>();
        if (words != null) {
            for (String word : words) {
                if (word == null) {
                    continue;
                }
                List<String> sequence = tokenize(word).stream().map(Token::normalized).toList();
                if (!sequence.isEmpty()) {
                    parsed.add(sequence);
                }
            }
        }
        this.entries = Set.copyOf(parsed);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public boolean containsProfanity(String text) {
        return !matches(text).isEmpty();
    }

    /** Replaces every character of each matched word/phrase with '*'; other text is untouched. */
    public String mask(String text) {
        List<int[]> spans = matches(text);
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text);
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1]; i++) {
                if (!Character.isWhitespace(out.charAt(i))) {
                    out.setCharAt(i, '*');
                }
            }
        }
        return out.toString();
    }

    /** Character spans [start, end) of matched entries in {@code text}. */
    List<int[]> matches(String text) {
        List<int[]> spans = new ArrayList<>();
        if (text == null || text.isEmpty() || entries.isEmpty()) {
            return spans;
        }
        List<Token> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            for (List<String> entry : entries) {
                if (i + entry.size() > tokens.size()) {
                    continue;
                }
                boolean match = true;
                for (int j = 0; j < entry.size() && match; j++) {
                    match = tokens.get(i + j).normalized().equals(entry.get(j));
                }
                if (match) {
                    spans.add(new int[]{tokens.get(i).start(), tokens.get(i + entry.size() - 1).end()});
                }
            }
        }
        return spans;
    }

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isWordCodePoint(cp)) {
                int start = i;
                while (i < text.length() && isWordCodePoint(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i));
                }
                String word = text.substring(start, i);
                tokens.add(new Token(Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT), start, i));
            } else {
                i += Character.charCount(cp);
            }
        }
        return tokens;
    }

    private static boolean isWordCodePoint(int cp) {
        if (Character.isLetterOrDigit(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }
}

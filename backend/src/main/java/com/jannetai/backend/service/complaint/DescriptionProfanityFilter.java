package com.jannetai.backend.service.complaint;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Audit GAP-054 (SRS 17.2): profanity filter for the complaint description.
 *
 * The list is configuration, not code (the SRS names no list, and choosing
 * one - English, Hindi, transliterated Hindi, regional languages - is a
 * product-owner decision):
 * <ul>
 *   <li>{@code COMPLAINT_PROFANITY_WORDS}: comma-separated words/phrases;</li>
 *   <li>{@code COMPLAINT_PROFANITY_WORDLIST_PATH}: a UTF-8 file, one entry per
 *       line, '#' starts a comment (for example a mounted secret/config file);</li>
 *   <li>{@code COMPLAINT_PROFANITY_ACTION}: {@code REJECT} (default; the
 *       submission fails with 400 and the citizen is asked to rephrase) or
 *       {@code MASK} (the matched words are stored as asterisks).</li>
 * </ul>
 * With no list configured the filter lets every description through and says
 * so once at startup - it never pretends to filter.
 */
@Slf4j
@Component
public class DescriptionProfanityFilter {

    public enum Action { REJECT, MASK }

    static final String REJECTION_MESSAGE =
            "Description contains language that is not allowed. Please rephrase and submit again.";

    private final ProfanityMatcher matcher;
    private final Action action;

    public DescriptionProfanityFilter(
            @Value("${app.complaint.profanity.words:}") String words,
            @Value("${app.complaint.profanity.wordlist-path:}") String wordlistPath,
            @Value("${app.complaint.profanity.action:REJECT}") String action) {
        List<String> entries = new ArrayList<>();
        if (words != null && !words.isBlank()) {
            entries.addAll(Arrays.asList(words.split(",")));
        }
        if (wordlistPath != null && !wordlistPath.isBlank()) {
            entries.addAll(readList(Path.of(wordlistPath.trim())));
        }
        this.matcher = new ProfanityMatcher(entries);
        this.action = parseAction(action);
        if (matcher.isEmpty()) {
            log.warn("Complaint description profanity filter has no word list configured "
                    + "(COMPLAINT_PROFANITY_WORDS / COMPLAINT_PROFANITY_WORDLIST_PATH) - descriptions are not filtered");
        } else {
            log.info("Complaint description profanity filter active: {} entries, action {}", matcher.size(), this.action);
        }
    }

    /**
     * @return the description to store (masked when the action is MASK)
     * @throws IllegalArgumentException (HTTP 400) when the action is REJECT and a listed word is present
     */
    public String apply(String description) {
        if (description == null || matcher.isEmpty() || !matcher.containsProfanity(description)) {
            return description;
        }
        if (action == Action.MASK) {
            return matcher.mask(description);
        }
        throw new IllegalArgumentException(REJECTION_MESSAGE);
    }

    public boolean isConfigured() {
        return !matcher.isEmpty();
    }

    static Action parseAction(String value) {
        if (value == null || value.isBlank()) {
            return Action.REJECT;
        }
        try {
            return Action.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("COMPLAINT_PROFANITY_ACTION must be REJECT or MASK, was: " + value.trim());
        }
    }

    static List<String> readList(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException e) {
            // Fail fast: a configured-but-unreadable list must not silently disable the filter.
            throw new IllegalStateException("COMPLAINT_PROFANITY_WORDLIST_PATH cannot be read: " + path, e);
        }
    }
}

package com.jannetai.backend.service.complaint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Audit GAP-054 (SRS 17.2 description profanity filter). NOT EXECUTED here via Maven. */
class ProfanityFilterTest {

    @Test
    void matcherIsWholeWordCaseInsensitiveAndUnicodeAware() {
        ProfanityMatcher m = new ProfanityMatcher(List.of("badword", "two words", "गाली"));
        assertThat(m.containsProfanity("a BADWORD here")).isTrue();
        assertThat(m.containsProfanity("notbadwordish")).isFalse();
        assertThat(m.containsProfanity("these two   words")).isTrue();
        assertThat(m.containsProfanity("यह गाली है")).isTrue();
        assertThat(m.containsProfanity("ｂａｄｗｏｒｄ")).isTrue(); // NFKC
        assertThat(m.mask("a badword, two words.")).isEqualTo("a *******, *** *****.");
    }

    @Test
    void rejectModeThrowsA400StyleError() {
        DescriptionProfanityFilter filter = new DescriptionProfanityFilter("badword", "", "REJECT");
        assertThatThrownBy(() -> filter.apply("so badword"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not allowed");
        assertThat(filter.apply("clean text")).isEqualTo("clean text");
        assertThat(filter.apply(null)).isNull();
    }

    @Test
    void maskModeStoresAsterisks() {
        DescriptionProfanityFilter filter = new DescriptionProfanityFilter("badword", "", "mask");
        assertThat(filter.apply("so badword")).isEqualTo("so *******");
    }

    @Test
    void emptyListFiltersNothingAndSaysSo() {
        DescriptionProfanityFilter filter = new DescriptionProfanityFilter("", "", "REJECT");
        assertThat(filter.isConfigured()).isFalse();
        assertThat(filter.apply("anything at all")).isEqualTo("anything at all");
    }

    @Test
    void wordListFileWithComments(@TempDir Path dir) throws Exception {
        Path list = dir.resolve("words.txt");
        Files.writeString(list, "# comment\nfileword\n\n  spaced  \n");
        DescriptionProfanityFilter filter = new DescriptionProfanityFilter("", list.toString(), "REJECT");
        assertThatThrownBy(() -> filter.apply("a fileword")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> filter.apply("a spaced one")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void misconfigurationFailsFast() {
        assertThatThrownBy(() -> new DescriptionProfanityFilter("x", "", "DELETE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DescriptionProfanityFilter("", "/nonexistent/words.txt", "REJECT"))
                .isInstanceOf(IllegalStateException.class);
    }
}

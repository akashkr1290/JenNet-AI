package com.jannetai.backend.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit GAP-054 (SRS 17.1). Same cases also run in the pure-JDK Phase 06 harness. NOT EXECUTED here via Maven. */
class PersonNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Ravi Kumar", "Al", "राम कुमार", "அருண் குமார்", "Anne Marie Singh"})
    void accepted(String name) {
        assertThat(PersonNames.isValid(name)).isTrue();
        assertThat(new PersonName.Validator().isValid(name, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "Ravi 2", "A. Kumar", "Mary-Jane", "O'Brien", "Ravi  Kumar", " Ravi", "Ravi ", "Ravi 😀"})
    void rejected(String name) {
        assertThat(PersonNames.isValid(name)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void lengthLimitsAndNull() {
        assertThat(PersonNames.isValid("a".repeat(100))).isTrue();
        assertThat(PersonNames.isValid("a".repeat(101))).isFalse();
        assertThat(PersonNames.isValid(null)).isFalse();
        assertThat(new PersonName.Validator().isValid(null, null)).isTrue(); // left to @NotBlank
    }
}

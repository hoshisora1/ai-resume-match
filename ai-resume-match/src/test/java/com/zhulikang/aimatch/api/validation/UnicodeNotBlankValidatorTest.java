package com.zhulikang.aimatch.api.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UnicodeNotBlankValidatorTest {
    private final UnicodeNotBlankValidator validator = new UnicodeNotBlankValidator();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u3000", "\u00A0", "\u3000\u00A0"})
    void rejectsNullEmptyAndUnicodeWhitespace(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "\u3000x", "\u00A0x", "\uD83D\uDE80"})
    void acceptsContentContainingANonWhitespaceCodePoint(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }
}

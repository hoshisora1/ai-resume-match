package com.zhulikang.aimatch.api.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class UnicodeNotBlankValidator implements ConstraintValidator<UnicodeNotBlank, CharSequence> {
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = Character.codePointAt(value, offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }
}

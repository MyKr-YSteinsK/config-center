package com.example.configcenter.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class UnicodeNameValidator implements ConstraintValidator<UnicodeName, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || isWellFormed(value);
    }

    public static boolean isWellFormed(String value) {
        return value.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE);
    }
}

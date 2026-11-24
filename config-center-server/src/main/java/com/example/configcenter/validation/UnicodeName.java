package com.example.configcenter.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Constraint(validatedBy = UnicodeNameValidator.class)
@Retention(RUNTIME)
@Target({FIELD, PARAMETER})
public @interface UnicodeName {
    String message() default "名称必须是有效的 Unicode 字符串";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

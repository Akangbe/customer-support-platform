package com.supportplatform.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * BCrypt reads at most 72 bytes and refuses to hash more. The limit is in
 * bytes, so {@code @Size} alone cannot enforce it: 50 accented characters are
 * 100 bytes. Without this, such a password is a 500 rather than a clear refusal.
 */
@Documented
@Constraint(validatedBy = FitsBcrypt.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface FitsBcrypt {

    String message() default "Password is too long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<FitsBcrypt, String> {

        private static final int MAX_BYTES = 72;

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}

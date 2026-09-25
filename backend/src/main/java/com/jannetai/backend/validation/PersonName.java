package com.jannetai.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Audit GAP-054: a person's full name per SRS 17.1 - see {@link PersonNames}.
 * {@code null} is left to {@code @NotBlank}.
 */
@Documented
@Constraint(validatedBy = PersonName.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {

    String message() default PersonNames.MESSAGE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PersonName, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || PersonNames.isValid(value);
        }
    }
}

package com.example.configcenter.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPropertiesTest {

    @Test
    void validation_acceptsDefaultsAndRejectsInvalidLimits() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            RateLimitProperties properties = new RateLimitProperties();
            assertTrue(validator.validate(properties).isEmpty());

            properties.setCapacity(0);
            properties.setRefillPerSecond(-1);
            properties.setMaxBuckets(0);

            Set<String> invalidProperties = validator.validate(properties).stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .collect(Collectors.toSet());

            assertEquals(Set.of("capacity", "refillPerSecond", "maxBuckets"), invalidProperties);
        }
    }
}

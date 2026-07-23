package com.example.configcenter.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWatchPropertiesTest {

    @Test
    void validation_acceptsDefaultsAndRejectsNonPositiveLimits() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            ConfigWatchProperties properties = new ConfigWatchProperties();
            assertTrue(validator.validate(properties).isEmpty());

            properties.setMaxPendingWaiters(0);
            properties.setMaxPendingPerNamespace(-1);

            Set<String> invalidProperties = validator.validate(properties).stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .collect(Collectors.toSet());

            assertEquals(
                    Set.of("maxPendingWaiters", "maxPendingPerNamespace"), invalidProperties);
        }
    }
}

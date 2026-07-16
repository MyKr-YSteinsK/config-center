package com.example.configcenter.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyPropertiesTest {

    @Test
    void validation_rejectsInvalidApiKeyEntries() {
        ApiKeyProperties.ApiKeyItem invalid = new ApiKeyProperties.ApiKeyItem();
        invalid.setKey(" ");
        invalid.setApp("x".repeat(101));
        invalid.setEnv("");
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.setApiKeys(List.of(invalid));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<String> invalidProperties = validator.validate(properties).stream()
                    .map(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .collect(Collectors.toSet());

            assertEquals(Set.of(
                    "apiKeys[0].key", "apiKeys[0].app", "apiKeys[0].env"),
                    invalidProperties);
        }
    }

    @Test
    void developmentKey_canBeOverriddenByEnvironmentProperty() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test-override", Map.of("CONFIG_CENTER_API_KEY", "external-dev-key")));
        new YamlPropertySourceLoader().load(
                        "application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        ApiKeyProperties properties = Binder.get(environment)
                .bind("security", Bindable.of(ApiKeyProperties.class))
                .orElseThrow(() -> new IllegalStateException("security properties were not bound"));

        assertEquals("external-dev-key", properties.getApiKeys().get(0).getKey());
        assertEquals("demo-app", properties.getApiKeys().get(0).getApp());
        assertEquals(1, properties.getApiKeys().size());
    }
}

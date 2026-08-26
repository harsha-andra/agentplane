package com.harshaandra.agentplane.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TenantCreateRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequestHasNoViolations() {
        var request = new TenantCreateRequest("Acme Corp", "acme", "4", "8Gi", 5);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void uppercaseSlugIsRejected() {
        var request = new TenantCreateRequest("Acme Corp", "Acme", "4", "8Gi", 5);
        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("slug"));
    }

    @Test
    void zeroMaxConcurrentRunsIsRejected() {
        var request = new TenantCreateRequest("Acme Corp", "acme", "4", "8Gi", 0);
        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("maxConcurrentRuns"));
    }

    @Test
    void blankNameIsRejected() {
        var request = new TenantCreateRequest("", "acme", "4", "8Gi", 5);
        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}

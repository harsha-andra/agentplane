package com.harshaandra.agentplane.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bean Validation on {@link RunSpecRequest}, exercised directly (no Spring context needed). */
class RunSpecRequestValidationTest {

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

    private static RunSpecRequest.ResourceSpec validResources() {
        return new RunSpecRequest.ResourceSpec("500m", "512Mi");
    }

    @Test
    void validRequestHasNoViolations() {
        RunSpecRequest request = new RunSpecRequest(
                UUID.randomUUID(), "agent", "image:1", "do something useful", "gpt-4o-mini",
                10, 60, Map.of(), validResources(), null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void missingTenantIdIsRejected() {
        RunSpecRequest request = new RunSpecRequest(
                null, "agent", "image:1", "prompt", "model", 10, 60, Map.of(), validResources(), null);

        Set<ConstraintViolation<RunSpecRequest>> violations = validator.validate(request);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void blankAgentNameIsRejected() {
        RunSpecRequest request = new RunSpecRequest(
                UUID.randomUUID(), "  ", "image:1", "prompt", "model", 10, 60, Map.of(), validResources(), null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("agentName"));
    }

    @Test
    void maxStepsOutOfRangeIsRejected() {
        RunSpecRequest request = new RunSpecRequest(
                UUID.randomUUID(), "agent", "image:1", "prompt", "model", 0, 60, Map.of(), validResources(), null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("maxSteps"));
    }

    @Test
    void blankNestedResourceFieldIsRejected() {
        RunSpecRequest request = new RunSpecRequest(
                UUID.randomUUID(), "agent", "image:1", "prompt", "model", 10, 60, Map.of(),
                new RunSpecRequest.ResourceSpec("", "512Mi"), null);

        assertThat(validator.validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("resources.cpu"));
    }

    @Test
    void nullEnvIsNormalizedToEmptyMap() {
        RunSpecRequest request = new RunSpecRequest(
                UUID.randomUUID(), "agent", "image:1", "prompt", "model", 10, 60, null, validResources(), null);

        assertThat(request.env()).isEmpty();
    }
}

package com.harshaandra.agentplane.api.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.orchestration.DuplicateSlugException;
import com.harshaandra.agentplane.orchestration.InvalidRunTransitionException;
import com.harshaandra.agentplane.orchestration.ResourceNotFoundException;
import com.harshaandra.agentplane.orchestration.TenantCapacityExceededException;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        ProblemDetail problem = handler.handleNotFound(new ResourceNotFoundException("Run not found: 123"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
        assertThat(problem.getDetail()).contains("Run not found");
    }

    @Test
    void invalidTransitionMapsTo409() {
        ProblemDetail problem = handler.handleInvalidTransition(
                new InvalidRunTransitionException(RunStatus.SUCCEEDED, RunStatus.RUNNING));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void capacityExceededMapsTo409() {
        ProblemDetail problem = handler.handleCapacityExceeded(new TenantCapacityExceededException(UUID.randomUUID(), 5));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getTitle()).isEqualTo("Tenant concurrency limit reached");
    }

    @Test
    void duplicateSlugMapsTo409() {
        ProblemDetail problem = handler.handleDuplicateSlug(new DuplicateSlugException("acme"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).contains("acme");
    }

    @Test
    void validationFailureMapsTo400WithFieldErrors() throws NoSuchMethodException {
        Method method = SampleController.class.getMethod("sample", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "agentName", "agentName is required"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ProblemDetail problem = handler.handleValidation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsKey("errors");
        @SuppressWarnings("unchecked")
        var errors = (java.util.Map<String, String>) problem.getProperties().get("errors");
        assertThat(errors).containsEntry("agentName", "agentName is required");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingDetail() {
        ProblemDetail problem = handler.handleUnexpected(new RuntimeException("some internal secret detail"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).doesNotContain("secret");
    }

    static class SampleController {
        public void sample(String arg) {
        }
    }
}

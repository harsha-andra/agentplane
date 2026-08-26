package com.harshaandra.agentplane.api.advice;

import com.harshaandra.agentplane.orchestration.DuplicateSlugException;
import com.harshaandra.agentplane.orchestration.InvalidRunTransitionException;
import com.harshaandra.agentplane.orchestration.ResourceNotFoundException;
import com.harshaandra.agentplane.orchestration.TenantCapacityExceededException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into RFC 7807 {@link ProblemDetail} responses, so every error the API
 * returns has a consistent, machine-parseable shape ({@code type}, {@code title}, {@code status},
 * {@code detail}, plus {@code errors} for validation failures) instead of a raw stack trace or an
 * ad hoc JSON body.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final URI VALIDATION_ERROR_TYPE = URI.create("https://agentplane.dev/problems/validation-error");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(InvalidRunTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidRunTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Invalid run state transition", ex.getMessage());
    }

    @ExceptionHandler(TenantCapacityExceededException.class)
    public ProblemDetail handleCapacityExceeded(TenantCapacityExceededException ex) {
        return problem(HttpStatus.CONFLICT, "Tenant concurrency limit reached", ex.getMessage());
    }

    @ExceptionHandler(DuplicateSlugException.class)
    public ProblemDetail handleDuplicateSlug(DuplicateSlugException ex) {
        return problem(HttpStatus.CONFLICT, "Tenant already exists", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields failed validation");
        problem.setType(VALIDATION_ERROR_TYPE);
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed", ex.getMessage());
        problem.setType(VALIDATION_ERROR_TYPE);
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

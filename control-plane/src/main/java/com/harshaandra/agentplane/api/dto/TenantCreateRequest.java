package com.harshaandra.agentplane.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Request body for {@code POST /api/v1/tenants}. */
public record TenantCreateRequest(
        @NotBlank(message = "name is required") String name,
        @NotBlank(message = "slug is required")
        @Pattern(regexp = "^[a-z][a-z0-9-]{1,48}$", message = "slug must be lowercase alphanumeric with hyphens")
        String slug,
        @NotBlank(message = "quotaCpu is required") String quotaCpu,
        @NotBlank(message = "quotaMemory is required") String quotaMemory,
        @Min(1) @Max(1000) int maxConcurrentRuns) {
}

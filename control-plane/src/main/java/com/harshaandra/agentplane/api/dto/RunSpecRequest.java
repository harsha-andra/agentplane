package com.harshaandra.agentplane.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/** Request body for {@code POST /api/v1/runs}. */
@Schema(name = "RunSpecRequest")
public record RunSpecRequest(
        @NotNull(message = "tenantId is required") UUID tenantId,
        @NotBlank(message = "agentName is required") String agentName,
        @NotBlank(message = "image is required") String image,
        @NotBlank(message = "prompt is required") @Size(max = 16_000) String prompt,
        @NotBlank(message = "model is required") String model,
        @Min(1) @Max(500) int maxSteps,
        @Min(1) @Max(86_400) int timeoutSeconds,
        Map<String, String> env,
        @NotNull @Valid ResourceSpec resources,
        @Schema(description = "Optional client-supplied idempotency key; if omitted one is generated. "
                + "Resubmitting the same key returns the original run instead of creating a duplicate.")
        String idempotencyKey) {

    public RunSpecRequest {
        if (env == null) {
            env = Map.of();
        }
    }

    public record ResourceSpec(
            @NotBlank(message = "resources.cpu is required") String cpu,
            @NotBlank(message = "resources.memory is required") String memory) {
    }
}

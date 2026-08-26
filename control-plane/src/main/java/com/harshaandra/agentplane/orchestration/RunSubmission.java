package com.harshaandra.agentplane.orchestration;

import java.util.Map;
import java.util.UUID;

/** Input to {@code RunOrchestrationService#submitRun}, decoupled from the api.dto request shape. */
public record RunSubmission(
        UUID tenantId,
        String agentName,
        String image,
        String prompt,
        String model,
        int maxSteps,
        int timeoutSeconds,
        Map<String, String> env,
        String resourceCpu,
        String resourceMemory,
        String idempotencyKey) {
}

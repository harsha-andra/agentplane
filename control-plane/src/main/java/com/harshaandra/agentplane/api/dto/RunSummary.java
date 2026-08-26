package com.harshaandra.agentplane.api.dto;

import com.harshaandra.agentplane.domain.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record RunSummary(
        UUID id,
        UUID tenantId,
        String tenantName,
        String agentName,
        RunStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs,
        String k8sJobName,
        String namespace,
        int attempt,
        String idempotencyKey) {
}

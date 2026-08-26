package com.harshaandra.agentplane.api.dto;

import com.harshaandra.agentplane.domain.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code RunSummary} plus full spec + live pod/execution detail. Returned by the single-run endpoints. */
public record RunDetail(
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
        String idempotencyKey,
        RunSpecView spec,
        String podPhase,
        Integer exitCode,
        int restartCount,
        String nodeName,
        String message,
        int stepCount,
        TokenUsage tokenUsage,
        BigDecimal costUsd) {
}

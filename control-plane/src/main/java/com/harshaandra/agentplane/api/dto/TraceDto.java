package com.harshaandra.agentplane.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TraceDto(
        String id,
        UUID runId,
        long seq,
        String type,
        String toolName,
        Instant startedAt,
        Long latencyMs,
        String status,
        Map<String, Object> payload,
        String error) {
}

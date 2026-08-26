package com.harshaandra.agentplane.api.dto;

import java.time.Instant;
import java.util.UUID;

public record TenantDto(
        UUID id,
        String name,
        String slug,
        String namespace,
        String quotaCpu,
        String quotaMemory,
        int maxConcurrentRuns,
        long activeRuns,
        Instant createdAt) {
}

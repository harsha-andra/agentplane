package com.harshaandra.agentplane.api.dto;

public record ToolLatencyDto(
        String toolName,
        long callCount,
        double avgLatencyMs,
        double p95LatencyMs,
        double errorRate) {
}

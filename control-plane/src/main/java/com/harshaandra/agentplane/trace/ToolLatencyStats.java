package com.harshaandra.agentplane.trace;

/** Result row of {@link TraceAnalyticsService#toolLatency(int)}. */
public record ToolLatencyStats(
        String toolName,
        long callCount,
        double avgLatencyMs,
        double p95LatencyMs,
        double errorRate) {
}

package com.harshaandra.agentplane.api.dto;

import java.time.Instant;
import java.util.List;

/** Response body for {@code GET /api/v1/analytics/overview} - powers the console's home dashboard. */
public record OverviewDto(
        long activeRuns,
        long queuedRuns,
        long streamDepth,
        double successRate24h,
        double p95DurationMs,
        double tokenSpend24h,
        List<RunsOverTimePoint> runsOverTime,
        List<ToolLatencyDto> toolLatency,
        List<TenantUtilization> tenantUtilization,
        List<RecentEvent> recentEvents) {

    public record RunsOverTimePoint(Instant ts, long succeeded, long failed, long cancelled) {
    }

    public record TenantUtilization(String tenantName, long activeRuns, int maxConcurrentRuns) {
    }

    public record RecentEvent(Instant ts, String eventType, String detail) {
    }
}

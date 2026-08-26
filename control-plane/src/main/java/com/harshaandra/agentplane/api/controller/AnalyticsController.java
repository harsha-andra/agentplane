package com.harshaandra.agentplane.api.controller;

import com.harshaandra.agentplane.api.dto.OverviewDto;
import com.harshaandra.agentplane.api.dto.OverviewDto.RecentEvent;
import com.harshaandra.agentplane.api.dto.OverviewDto.RunsOverTimePoint;
import com.harshaandra.agentplane.api.dto.OverviewDto.TenantUtilization;
import com.harshaandra.agentplane.api.dto.ToolLatencyDto;
import com.harshaandra.agentplane.api.mapper.AnalyticsMapper;
import com.harshaandra.agentplane.config.StreamProperties;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import com.harshaandra.agentplane.trace.TraceAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Tool latency and control-plane dashboard metrics")
public class AnalyticsController {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.PENDING, RunStatus.SCHEDULED, RunStatus.RUNNING);
    private static final List<RunStatus> TERMINAL = List.of(
            RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT);

    private final TraceAnalyticsService traceAnalyticsService;
    private final AnalyticsMapper analyticsMapper;
    private final AgentRunRepository agentRunRepository;
    private final TenantRepository tenantRepository;
    private final AuditEventRepository auditEventRepository;
    private final StreamOperations<String, Object, Object> streamOperations;
    private final StreamProperties streamProperties;

    @GetMapping("/tool-latency")
    @Operation(summary = "Per-tool call volume, avg/p95 latency and error rate over the trailing window",
            description = "Backed by a real MongoDB aggregation pipeline (match -> group -> sort) over run_traces")
    public List<ToolLatencyDto> toolLatency(@RequestParam(defaultValue = "7") int days) {
        return analyticsMapper.toDtoList(traceAnalyticsService.toolLatency(days));
    }

    @GetMapping("/overview")
    @Operation(summary = "Control-plane dashboard overview")
    public OverviewDto overview() {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant since7d = Instant.now().minus(7, ChronoUnit.DAYS);

        long activeRuns = agentRunRepository.countByStatusIn(ACTIVE);
        long queuedRuns = agentRunRepository.countByStatusIn(List.of(RunStatus.PENDING));
        long streamDepth = safeStreamDepth();

        List<AgentRun> last24h = agentRunRepository.findByCreatedAtAfter(since24h);
        List<AgentRun> terminalLast24h = last24h.stream().filter(r -> r.getStatus().isTerminal()).toList();

        double successRate24h = rate(terminalLast24h);
        double p95DurationMs = p95Duration(terminalLast24h);
        double tokenSpend24h = terminalLast24h.stream()
                .map(AgentRun::getCostUsd)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .sum();

        List<RunsOverTimePoint> runsOverTime = bucketByDay(agentRunRepository.findByCreatedAtAfter(since7d));
        List<ToolLatencyDto> toolLatency = analyticsMapper.toDtoList(traceAnalyticsService.toolLatency(7));
        List<TenantUtilization> tenantUtilization = tenantUtilization();
        List<RecentEvent> recentEvents = auditEventRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(e -> new RecentEvent(e.getCreatedAt(), e.getEventType(), e.getDetail()))
                .toList();

        return new OverviewDto(
                activeRuns, queuedRuns, streamDepth, successRate24h, p95DurationMs, tokenSpend24h,
                runsOverTime, toolLatency, tenantUtilization, recentEvents);
    }

    private long safeStreamDepth() {
        try {
            Long size = streamOperations.size(streamProperties.getStreamKey());
            return size == null ? 0 : size;
        } catch (Exception e) {
            return 0;
        }
    }

    private static double rate(List<AgentRun> terminal) {
        if (terminal.isEmpty()) {
            return 0.0;
        }
        long succeeded = terminal.stream().filter(r -> r.getStatus() == RunStatus.SUCCEEDED).count();
        return round((double) succeeded / terminal.size());
    }

    private static double p95Duration(List<AgentRun> terminal) {
        List<Long> durations = terminal.stream()
                .map(AgentRun::durationMs)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (durations.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(0.95 * durations.size()) - 1;
        index = Math.max(0, Math.min(index, durations.size() - 1));
        return durations.get(index);
    }

    private static List<RunsOverTimePoint> bucketByDay(List<AgentRun> runs) {
        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (AgentRun run : runs) {
            if (!run.getStatus().isTerminal()) {
                continue;
            }
            LocalDate day = run.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
            long[] counts = buckets.computeIfAbsent(day, d -> new long[3]); // succeeded, failed, cancelled(+timedOut)
            switch (run.getStatus()) {
                case SUCCEEDED -> counts[0]++;
                case FAILED -> counts[1]++;
                case CANCELLED, TIMED_OUT -> counts[2]++;
                default -> { }
            }
        }
        List<RunsOverTimePoint> points = new ArrayList<>();
        buckets.forEach((day, counts) -> points.add(new RunsOverTimePoint(
                day.atStartOfDay(ZoneOffset.UTC).toInstant(), counts[0], counts[1], counts[2])));
        return points;
    }

    private List<TenantUtilization> tenantUtilization() {
        return tenantRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(tenant -> new TenantUtilization(
                        tenant.getName(),
                        agentRunRepository.countByTenantIdAndStatusIn(tenant.getId(), ACTIVE),
                        tenant.getMaxConcurrentRuns()))
                .sorted(Comparator.comparing(TenantUtilization::tenantName))
                .toList();
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

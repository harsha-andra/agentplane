package com.harshaandra.agentplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.trace.RunTrace;
import com.harshaandra.agentplane.trace.TagFrequency;
import com.harshaandra.agentplane.trace.ToolLatencyStats;
import com.harshaandra.agentplane.trace.TraceAnalyticsService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the real MongoDB aggregation pipeline in {@link TraceAnalyticsService} against a
 * genuine MongoDB instance via Testcontainers - see README.md for how to run this
 * ({@code mvn verify -Pintegration}, needs Docker). Excluded from the default {@code mvn verify}.
 */
@Tag("integration")
@DataMongoTest
@Testcontainers
class TraceAnalyticsServiceIntegrationTest {

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    private TraceAnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new TraceAnalyticsService(mongoTemplate);
        mongoTemplate.dropCollection(RunTrace.class);
    }

    @Test
    void toolLatencyComputesAvgP95AndErrorRatePerTool() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        // web_search: 9 successes at 100ms, 1 error at 1000ms -> avg=190, p95=1000 (index 9 of 10), errorRate=0.1
        for (int i = 0; i < 9; i++) {
            saveToolCall(runId, "web_search", now, 100L, RunTrace.TraceStatus.SUCCESS);
        }
        saveToolCall(runId, "web_search", now, 1000L, RunTrace.TraceStatus.ERROR);

        // code_exec: single call, always succeeds
        saveToolCall(runId, "code_exec", now, 50L, RunTrace.TraceStatus.SUCCESS);

        // A LOG-type trace with no toolName - must be excluded from the aggregation entirely.
        mongoTemplate.save(new RunTrace(runId, 100, RunTrace.TraceType.LOG, null, now, null,
                RunTrace.TraceStatus.SUCCESS, Map.of(), null));

        List<ToolLatencyStats> stats = service.toolLatency(7);

        assertThat(stats).hasSize(2);
        ToolLatencyStats webSearch = stats.stream().filter(s -> s.toolName().equals("web_search")).findFirst().orElseThrow();
        assertThat(webSearch.callCount()).isEqualTo(10);
        assertThat(webSearch.avgLatencyMs()).isEqualTo(190.0);
        assertThat(webSearch.p95LatencyMs()).isEqualTo(1000.0);
        assertThat(webSearch.errorRate()).isEqualTo(0.1);

        ToolLatencyStats codeExec = stats.stream().filter(s -> s.toolName().equals("code_exec")).findFirst().orElseThrow();
        assertThat(codeExec.callCount()).isEqualTo(1);
        assertThat(codeExec.errorRate()).isEqualTo(0.0);
    }

    @Test
    void toolLatencyExcludesCallsOutsideTheWindow() {
        UUID runId = UUID.randomUUID();
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);
        saveToolCall(runId, "stale_tool", tenDaysAgo, 100L, RunTrace.TraceStatus.SUCCESS);

        List<ToolLatencyStats> stats = service.toolLatency(7);

        assertThat(stats).noneMatch(s -> s.toolName().equals("stale_tool"));
    }

    @Test
    void errorTagFrequencyUnwindsTagsArray() {
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        mongoTemplate.save(new RunTrace(runId, 1, RunTrace.TraceType.TOOL_CALL, "web_search", now, 500L,
                RunTrace.TraceStatus.ERROR, Map.of("tags", List.of("timeout", "retry")), "boom"));
        mongoTemplate.save(new RunTrace(runId, 2, RunTrace.TraceType.TOOL_CALL, "sql_query", now, 500L,
                RunTrace.TraceStatus.ERROR, Map.of("tags", List.of("timeout")), "boom"));

        List<TagFrequency> frequencies = service.errorTagFrequency(7);

        assertThat(frequencies).extracting(TagFrequency::tag).contains("timeout", "retry");
        assertThat(frequencies.stream().filter(f -> f.tag().equals("timeout")).findFirst().orElseThrow().count())
                .isEqualTo(2);
    }

    private void saveToolCall(UUID runId, String tool, Instant startedAt, long latencyMs, RunTrace.TraceStatus status) {
        mongoTemplate.save(new RunTrace(runId, System.nanoTime(), RunTrace.TraceType.TOOL_CALL, tool, startedAt,
                latencyMs, status, Map.of("attempt", 1), status == RunTrace.TraceStatus.ERROR ? "failed" : null));
    }
}

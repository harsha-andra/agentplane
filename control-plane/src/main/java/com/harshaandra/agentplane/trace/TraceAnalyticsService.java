package com.harshaandra.agentplane.trace;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * Analytics over the raw trace stream, computed with real MongoDB aggregation pipelines rather
 * than pulling documents into the JVM and folding over them with Java streams.
 *
 * <p><b>Why this lives next to Mongo, and not in Postgres:</b> {@link RunTrace} rows are the
 * high-volume, schema-varying half of the system - every tool call and log line an agent
 * produces, with a payload shape that differs per tool and evolves without a migration. Postgres
 * (see {@code domain.AgentRun}) holds the low-volume, fixed-shape, transactional half: the run
 * record itself, joined to its tenant. Putting traces in Mongo means adding a new tool or a new
 * payload field never requires a schema migration, and the aggregation framework lets us compute
 * roll-ups (avg/p95 latency, error rate per tool) at the database layer instead of shipping
 * millions of trace documents to the application to fold in memory.
 */
@Service
@RequiredArgsConstructor
public class TraceAnalyticsService {

    private static final String COLLECTION = "run_traces";

    private final MongoTemplate mongoTemplate;

    /**
     * {@code match -> group -> project -> sort} pipeline: filters traces of type
     * {@code TOOL_CALL} within the trailing window, groups by tool name computing call count,
     * average latency and error count, then sorts by call volume descending. The p95 is not
     * computed inside the pipeline itself - MongoDB's {@code $percentile} accumulator requires
     * MongoDB 7+, which is not guaranteed on every self-hosted deployment this runs against -
     * so the pipeline instead {@code $push}es the raw latencies per group and the (small,
     * already-aggregated) per-tool array is reduced to a p95 in application code.
     */
    public List<ToolLatencyStats> toolLatency(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("type").is(RunTrace.TraceType.TOOL_CALL.name())
                        .and("startedAt").gte(since)
                        .and("toolName").ne(null)),
                Aggregation.group("toolName")
                        .count().as("callCount")
                        .avg("latencyMs").as("avgLatencyMs")
                        .push("latencyMs").as("latencies")
                        .sum(ConditionalOperators.when(Criteria.where("status").is(RunTrace.TraceStatus.ERROR.name()))
                                .then(1)
                                .otherwise(0))
                        .as("errorCount"),
                Aggregation.project("callCount", "avgLatencyMs", "errorCount", "latencies")
                        .and("_id").as("toolName"),
                Aggregation.sort(Sort.Direction.DESC, "callCount")
        );

        AggregationResults<ToolLatencyGroup> results =
                mongoTemplate.aggregate(aggregation, COLLECTION, ToolLatencyGroup.class);

        List<ToolLatencyStats> stats = new ArrayList<>();
        for (ToolLatencyGroup group : results.getMappedResults()) {
            stats.add(new ToolLatencyStats(
                    group.toolName(),
                    group.callCount(),
                    round(group.avgLatencyMs()),
                    round(percentile95(group.latencies())),
                    round(group.callCount() == 0 ? 0.0 : (double) group.errorCount() / group.callCount())));
        }
        return stats;
    }

    /**
     * A secondary aggregation demonstrating {@code $unwind}: unlike {@link #toolLatency(int)}
     * (where unwinding an array field would fan out documents and corrupt the call-count /
     * average-latency statistics), tag frequency is a case where fanning out is exactly the
     * desired semantic - a trace tagged {@code ["timeout", "retry"]} should count once towards
     * each tag. Pipeline: {@code match -> unwind -> group -> sort}.
     */
    @SuppressWarnings("unchecked")
    public List<TagFrequency> errorTagFrequency(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").is(RunTrace.TraceStatus.ERROR.name())
                        .and("startedAt").gte(since)
                        .and("payload.tags").exists(true)),
                Aggregation.unwind("payload.tags"),
                Aggregation.group("payload.tags").count().as("count"),
                Aggregation.project("count").and("_id").as("tag"),
                Aggregation.sort(Sort.Direction.DESC, "count")
        );

        AggregationResults<TagFrequencyGroup> results =
                mongoTemplate.aggregate(aggregation, COLLECTION, TagFrequencyGroup.class);

        List<TagFrequency> out = new ArrayList<>();
        for (TagFrequencyGroup g : results.getMappedResults()) {
            out.add(new TagFrequency(g.tag(), g.count()));
        }
        return out;
    }

    private static double percentile95(List<Long> latencies) {
        if (latencies == null || latencies.isEmpty()) {
            return 0.0;
        }
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record ToolLatencyGroup(
            String toolName, long callCount, double avgLatencyMs, long errorCount, List<Long> latencies) {
    }

    private record TagFrequencyGroup(String tag, long count) {
    }
}

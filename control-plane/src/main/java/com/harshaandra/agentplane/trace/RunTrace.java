package com.harshaandra.agentplane.trace;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One entry in the raw execution trace of a run: a log line, a tool invocation, or a step
 * boundary. Stored in MongoDB rather than Postgres because this data has no fixed shape (the
 * {@code payload} varies per tool and per agent framework) and arrives in far higher volume than
 * anything that needs a join - exactly the "SQL for records, NoSQL for logs" split described in
 * the class comment on {@code TraceAnalyticsService}.
 */
@Document(collection = "run_traces")
@CompoundIndexes({
        @CompoundIndex(name = "run_seq_idx", def = "{'runId': 1, 'seq': 1}")
})
@Getter
public class RunTrace {

    @Id
    private String id;

    @Indexed
    private UUID runId;

    private long seq;

    private TraceType type;

    @Indexed
    private String toolName;

    @Indexed
    private Instant startedAt;

    private Long latencyMs;

    private TraceStatus status;

    /** Schemaless payload - shape depends entirely on {@link #type} and {@link #toolName}. */
    private Map<String, Object> payload;

    private String error;

    protected RunTrace() {
    }

    public RunTrace(
            UUID runId,
            long seq,
            TraceType type,
            String toolName,
            Instant startedAt,
            Long latencyMs,
            TraceStatus status,
            Map<String, Object> payload,
            String error) {
        this.runId = runId;
        this.seq = seq;
        this.type = type;
        this.toolName = toolName;
        this.startedAt = startedAt;
        this.latencyMs = latencyMs;
        this.status = status;
        this.payload = payload;
        this.error = error;
    }

    public enum TraceType {
        LOG,
        TOOL_CALL,
        STEP
    }

    public enum TraceStatus {
        RUNNING,
        SUCCESS,
        ERROR
    }
}

package com.harshaandra.agentplane.config;

import lombok.Data;

/** Binds {@code agentplane.stream.*} - see {@code application.yml} for defaults and docs. */
@Data
public class StreamProperties {

    /** Whether the stream producer/consumer are active at all (off in the context-load test). */
    private boolean enabled = true;

    private String streamKey = "agentplane:runs";

    private String consumerGroup = "agentplane-workers";

    private String consumerName = "worker-" + System.currentTimeMillis() % 10000;

    private long pollTimeoutMs = 2000;

    private int pollBatchSize = 10;

    /**
     * How long a message may sit claimed-but-unacknowledged before another worker is allowed to
     * reclaim it via {@code XCLAIM}/{@code XAUTOCLAIM} - i.e. how long we assume a worker that
     * picked up a job is still alive and working on it before treating it as dead.
     */
    private long visibilityTimeoutMs = 60_000;

    private long idempotencyTtlSeconds = 3600;
}

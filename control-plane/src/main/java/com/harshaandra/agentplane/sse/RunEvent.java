package com.harshaandra.agentplane.sse;

import com.harshaandra.agentplane.domain.RunStatus;
import java.time.Instant;
import java.util.UUID;

/** One event on a run's live SSE stream: a status transition or a log line. */
public record RunEvent(
        long seq,
        UUID runId,
        Instant ts,
        String level,
        String source,
        String message,
        RunStatus phase) {
}

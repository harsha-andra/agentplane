package com.harshaandra.agentplane.sse;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Turns run lifecycle activity into {@link RunEvent}s broadcast over SSE. */
@Service
@RequiredArgsConstructor
public class RunEventPublisher {

    private final SseEmitterRegistry registry;
    private final Map<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

    public void publishStatusChange(AgentRun run, String message) {
        RunEvent event = new RunEvent(
                nextSeq(run.getId()),
                run.getId(),
                Instant.now(),
                "INFO",
                "orchestrator",
                message != null ? message : "status changed to " + run.getStatus(),
                run.getStatus());
        registry.broadcast(run.getId(), "status", event);
        if (run.getStatus().isTerminal()) {
            registry.complete(run.getId());
            sequences.remove(run.getId());
        }
    }

    public void publishLog(UUID runId, String level, String source, String message, RunStatus phase) {
        RunEvent event = new RunEvent(nextSeq(runId), runId, Instant.now(), level, source, message, phase);
        registry.broadcast(runId, "log", event);
    }

    @Scheduled(fixedDelayString = "${agentplane.sse.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        registry.heartbeat();
    }

    private long nextSeq(UUID runId) {
        return sequences.computeIfAbsent(runId, id -> new AtomicLong()).incrementAndGet();
    }
}

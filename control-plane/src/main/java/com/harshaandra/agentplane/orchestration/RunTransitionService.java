package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.AuditEvent;
import com.harshaandra.agentplane.sse.RunEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place a run's {@link RunStatus} is ever changed. Every transition is validated by
 * {@link RunStateMachine}, persisted, written to the append-only audit log, and broadcast to any
 * live SSE subscribers for that run - so the API layer, the stream consumer, the pod-status
 * watcher and the Noop simulator all go through one code path instead of duplicating this logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunTransitionService {

    private final AgentRunRepository agentRunRepository;
    private final AuditEventRepository auditEventRepository;
    private final RunStateMachine stateMachine;
    private final RunEventPublisher runEventPublisher;
    private final MeterRegistry meterRegistry;

    @Transactional
    public AgentRun transition(UUID runId, RunStatus to, String message) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run " + runId));
        return transition(run, to, message);
    }

    @Transactional
    public AgentRun transition(AgentRun run, RunStatus to, String message) {
        RunStatus from = run.getStatus();
        if (from == to) {
            return run; // no-op, e.g. duplicate watcher tick
        }
        stateMachine.validateTransition(from, to);
        run.applyStatus(to);
        if (message != null) {
            run.appendMessage(message);
        }
        AgentRun saved = agentRunRepository.save(run);

        auditEventRepository.save(AuditEvent.of(
                saved.getTenant().getId(),
                saved.getId(),
                "RUN_" + to.name(),
                "status " + from + " -> " + to + (message != null ? " (" + message + ")" : "")));

        runEventPublisher.publishStatusChange(saved, message);
        log.info("Run {} transitioned {} -> {}", saved.getId(), from, to);

        if (to.isTerminal() && saved.getStartedAt() != null && saved.getFinishedAt() != null) {
            Duration duration = Duration.between(saved.getStartedAt(), saved.getFinishedAt());
            meterRegistry.timer("agentplane_run_duration", "status", to.name(), "agent", saved.getAgentName())
                    .record(duration);
        }
        return saved;
    }

    /** Updates descriptive pod fields without a status transition (used by the pod watcher). */
    @Transactional
    public void updatePodStatus(UUID runId, String podPhase, Integer exitCode, int restartCount, String nodeName) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.updatePodStatus(podPhase, exitCode, restartCount, nodeName);
            agentRunRepository.save(run);
        });
    }

    @Transactional
    public void assignJob(UUID runId, String k8sJobName, String namespace) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.assignJob(k8sJobName, namespace);
            agentRunRepository.save(run);
        });
    }
}

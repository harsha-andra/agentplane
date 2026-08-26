package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.config.StreamProperties;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.stream.IdempotencyGuard;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes a run once it has been dequeued from the Redis Stream by
 * {@code stream.RunStreamConsumer}: guards against double-launch on redelivery, then hands off to
 * whichever {@link JobLauncher} is active.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunLaunchProcessor {

    private final AgentRunRepository agentRunRepository;
    private final RunTransitionService transitionService;
    private final JobLauncher jobLauncher;
    private final IdempotencyGuard idempotencyGuard;
    private final StreamProperties streamProperties;

    @Transactional
    public void processQueuedRun(UUID runId, String idempotencyKey, int attempt) {
        String guardKey = "agentplane:run-launch:" + runId;
        if (!idempotencyGuard.tryAcquire(guardKey, Duration.ofSeconds(streamProperties.getIdempotencyTtlSeconds()))) {
            log.info("Run {} was already launched by another worker (idempotency guard hit on redelivery) - "
                    + "skipping duplicate execution", runId);
            return;
        }

        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Stream message referenced unknown run {} - ignoring", runId);
            return;
        }
        if (run.getStatus() != RunStatus.PENDING) {
            log.info("Run {} is already {} - nothing to launch", runId, run.getStatus());
            return;
        }

        Tenant tenant = run.getTenant();
        AgentRun scheduled = transitionService.transition(run, RunStatus.SCHEDULED,
                "picked up by worker " + streamProperties.getConsumerName() + " (attempt " + attempt + ")");

        JobLauncher.LaunchedJob launched = jobLauncher.launchJob(scheduled, tenant);
        transitionService.assignJob(runId, launched.jobName(), launched.namespace());
        log.info("Run {} launched as job {} in namespace {}", runId, launched.jobName(), launched.namespace());
    }
}

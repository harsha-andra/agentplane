package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.sse.RunEventPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * The default {@link JobLauncher}: no Kubernetes cluster required. Instead of creating a real
 * Job, it simulates one - scheduling delayed callbacks that walk the run through
 * SCHEDULED -&gt; RUNNING -&gt; SUCCEEDED/FAILED and emitting a few fake log lines along the way -
 * so a clone of this repository is fully explorable (API, SSE stream, analytics) via
 * docker-compose alone, with no cluster to stand up.
 */
@Service
@ConditionalOnProperty(prefix = "agentplane.k8s", name = "enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class NoopJobLauncher implements JobLauncher {

    private final RunTransitionService transitionService;
    private final RunEventPublisher eventPublisher;
    private final AgentRunRepository agentRunRepository;
    private final TaskScheduler taskScheduler;

    private final Map<UUID, List<ScheduledFuture<?>>> scheduled = new ConcurrentHashMap<>();

    @Override
    public void provisionTenantNamespace(Tenant tenant) {
        log.info("[noop] would provision namespace '{}' (cpu={}, memory={}) for tenant '{}' - "
                        + "no Kubernetes cluster configured (agentplane.k8s.enabled=false)",
                tenant.getNamespace(), tenant.getQuotaCpu(), tenant.getQuotaMemory(), tenant.getSlug());
    }

    @Override
    public LaunchedJob launchJob(AgentRun run, Tenant tenant) {
        UUID runId = run.getId();
        String jobName = "noop-job-" + runId;

        List<ScheduledFuture<?>> futures = new CopyOnWriteArrayList<>();
        futures.add(schedule(Duration.ofMillis(500), () -> advance(runId, RunStatus.SCHEDULED, "job scheduled (noop)")));
        futures.add(schedule(Duration.ofSeconds(2), () -> {
            advance(runId, RunStatus.RUNNING, "container started (noop)");
            eventPublisher.publishLog(runId, "INFO", "agent", "agent process started", RunStatus.RUNNING);
        }));
        futures.add(schedule(Duration.ofSeconds(4), () ->
                eventPublisher.publishLog(runId, "INFO", "agent", "executing step 1/" + run.getMaxSteps(), RunStatus.RUNNING)));
        futures.add(schedule(Duration.ofSeconds(6), () -> {
            boolean succeed = Math.floorMod(runId.hashCode(), 10) != 0; // ~90% success rate
            if (succeed) {
                transitionService.updatePodStatus(runId, "Succeeded", 0, 0, "noop-node");
                advance(runId, RunStatus.SUCCEEDED, "run completed successfully (noop)");
            } else {
                transitionService.updatePodStatus(runId, "Failed", 1, 0, "noop-node");
                advance(runId, RunStatus.FAILED, "run failed (noop, simulated)");
            }
            scheduled.remove(runId);
        }));
        scheduled.put(runId, futures);

        return new LaunchedJob(jobName, tenant.getNamespace());
    }

    @Override
    public void cancelJob(AgentRun run) {
        List<ScheduledFuture<?>> futures = scheduled.remove(run.getId());
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
        }
    }

    @Override
    public Optional<PodStatusSnapshot> getPodStatus(AgentRun run) {
        // The Noop launcher drives run/pod status directly via scheduled transitions above,
        // rather than being polled - there is no external process to observe.
        return Optional.empty();
    }

    private void advance(UUID runId, RunStatus to, String message) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            if (run.getStatus().isTerminal() || run.getStatus() == to) {
                return;
            }
            transitionService.transition(runId, to, message);
        });
    }

    private ScheduledFuture<?> schedule(Duration delay, Runnable task) {
        return taskScheduler.schedule(task, Instant.now().plus(delay));
    }
}

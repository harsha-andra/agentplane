package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls pod status for in-flight runs and drives SCHEDULED -&gt; RUNNING -&gt;
 * SUCCEEDED/FAILED/TIMED_OUT transitions from what Kubernetes reports. Only active with a real
 * cluster ({@link Fabric8JobLauncher}); {@link NoopJobLauncher} drives its own simulated
 * transitions directly and needs no polling.
 */
@Component
@ConditionalOnProperty(prefix = "agentplane.k8s", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class PodStatusWatcher {

    private static final List<RunStatus> WATCHED = List.of(RunStatus.SCHEDULED, RunStatus.RUNNING);

    private final AgentRunRepository agentRunRepository;
    private final JobLauncher jobLauncher;
    private final RunTransitionService transitionService;

    @Scheduled(fixedDelayString = "${agentplane.k8s.pod-watch-interval-ms:5000}")
    public void poll() {
        for (AgentRun run : agentRunRepository.findByStatusIn(WATCHED)) {
            checkTimeout(run);
            if (run.getStatus().isTerminal()) {
                continue;
            }
            jobLauncher.getPodStatus(run).ifPresent(snapshot -> {
                transitionService.updatePodStatus(run.getId(), snapshot.podPhase(), snapshot.exitCode(),
                        snapshot.restartCount(), snapshot.nodeName());

                if (run.getStatus() == RunStatus.SCHEDULED && "Running".equalsIgnoreCase(snapshot.podPhase())) {
                    transitionService.transition(run.getId(), RunStatus.RUNNING, "pod is running");
                } else if ("Succeeded".equalsIgnoreCase(snapshot.podPhase())) {
                    transitionService.transition(run.getId(), RunStatus.SUCCEEDED, "pod succeeded");
                } else if ("Failed".equalsIgnoreCase(snapshot.podPhase())) {
                    transitionService.transition(run.getId(), RunStatus.FAILED,
                            "pod failed, exitCode=" + snapshot.exitCode());
                }
            });
        }
    }

    private void checkTimeout(AgentRun run) {
        if (run.getStatus().isTerminal()) {
            return;
        }
        Instant deadline = run.getCreatedAt().plusSeconds(run.getTimeoutSeconds());
        if (Instant.now().isAfter(deadline)) {
            jobLauncher.cancelJob(run);
            transitionService.transition(run.getId(), RunStatus.TIMED_OUT,
                    "exceeded timeoutSeconds=" + run.getTimeoutSeconds());
        }
    }
}

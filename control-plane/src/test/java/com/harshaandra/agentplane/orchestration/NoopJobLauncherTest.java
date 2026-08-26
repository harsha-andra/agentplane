package com.harshaandra.agentplane.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.sse.RunEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

/**
 * Verifies the Noop launcher's simulated lifecycle without waiting on real time: the mocked
 * {@link TaskScheduler} runs each scheduled callback synchronously and immediately.
 */
@ExtendWith(MockitoExtension.class)
class NoopJobLauncherTest {

    @Mock
    private RunTransitionService transitionService;
    @Mock
    private RunEventPublisher eventPublisher;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private TaskScheduler taskScheduler;

    private NoopJobLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new NoopJobLauncher(transitionService, eventPublisher, agentRunRepository, taskScheduler);
    }

    @Test
    void launchJobDrivesRunThroughSimulatedLifecycle() {
        // Run every scheduled callback synchronously the moment it's scheduled, instead of
        // waiting on the real delay - keeps this a fast unit test.
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return mock(ScheduledFuture.class);
        });

        Tenant tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "idem-key");
        // The repository always reports the run as still PENDING - transitionService itself is
        // mocked, so this fixture's state never actually advances; each simulated step's
        // "already terminal / already there" guard just always evaluates false, which is exactly
        // what lets every one of the four scheduled steps run through.
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        JobLauncher.LaunchedJob launched = launcher.launchJob(run, tenant);

        assertThat(launched.jobName()).contains(run.getId().toString());
        assertThat(launched.namespace()).isEqualTo(tenant.getNamespace());

        ArgumentCaptor<RunStatus> statusCaptor = ArgumentCaptor.forClass(RunStatus.class);
        verify(transitionService, times(3)).transition(eq(run.getId()), statusCaptor.capture(), any());
        assertThat(statusCaptor.getAllValues().get(0)).isEqualTo(RunStatus.SCHEDULED);
        assertThat(statusCaptor.getAllValues().get(1)).isEqualTo(RunStatus.RUNNING);
        assertThat(statusCaptor.getAllValues().get(2)).isIn(RunStatus.SUCCEEDED, RunStatus.FAILED);
    }

    @Test
    void cancelJobCancelsAllScheduledFutures() {
        Tenant tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "idem-key-2");
        // Note: no findById stub here - the scheduled runnables are captured but never executed
        // below, so advance() (which would call findById) never runs.

        List<ScheduledFuture<?>> createdFutures = new java.util.ArrayList<>();
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            createdFutures.add(future);
            return future; // do NOT execute - we want to cancel before they'd run
        });

        launcher.launchJob(run, tenant);
        launcher.cancelJob(run);

        assertThat(createdFutures).isNotEmpty();
        for (ScheduledFuture<?> future : createdFutures) {
            verify(future).cancel(false);
        }
    }

    @Test
    void provisionTenantNamespaceDoesNotThrow() {
        Tenant tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
        launcher.provisionTenantNamespace(tenant);
    }

    @Test
    void getPodStatusIsAlwaysEmpty() {
        Tenant tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "idem-key-3");

        assertThat(launcher.getPodStatus(run)).isEmpty();
    }
}

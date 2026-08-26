package com.harshaandra.agentplane.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshaandra.agentplane.config.StreamProperties;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.stream.IdempotencyGuard;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunLaunchProcessorTest {

    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private RunTransitionService transitionService;
    @Mock
    private JobLauncher jobLauncher;
    @Mock
    private IdempotencyGuard idempotencyGuard;

    private RunLaunchProcessor processor;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        StreamProperties properties = new StreamProperties();
        processor = new RunLaunchProcessor(agentRunRepository, transitionService, jobLauncher, idempotencyGuard, properties);
        tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
    }

    @Test
    void skipsWhenIdempotencyGuardAlreadyHeld() {
        UUID runId = UUID.randomUUID();
        when(idempotencyGuard.tryAcquire(eq("agentplane:run-launch:" + runId), any(Duration.class))).thenReturn(false);

        processor.processQueuedRun(runId, "key", 1);

        verify(agentRunRepository, never()).findById(any());
        verify(jobLauncher, never()).launchJob(any(), any());
    }

    @Test
    void skipsWhenRunUnknown() {
        UUID runId = UUID.randomUUID();
        when(idempotencyGuard.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(agentRunRepository.findById(runId)).thenReturn(Optional.empty());

        processor.processQueuedRun(runId, "key", 1);

        verify(jobLauncher, never()).launchJob(any(), any());
    }

    @Test
    void skipsWhenRunAlreadyPastPending() {
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key");
        run.applyStatus(RunStatus.SCHEDULED);
        when(idempotencyGuard.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        processor.processQueuedRun(run.getId(), "key", 1);

        verify(jobLauncher, never()).launchJob(any(), any());
    }

    @Test
    void launchesJobAndAssignsItOnHappyPath() {
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key");
        when(idempotencyGuard.tryAcquire(anyString(), any(Duration.class))).thenReturn(true);
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(transitionService.transition(eq(run), eq(RunStatus.SCHEDULED), anyString())).thenReturn(run);
        when(jobLauncher.launchJob(run, tenant)).thenReturn(new JobLauncher.LaunchedJob("job-1", "tenant-acme"));

        processor.processQueuedRun(run.getId(), "key", 2);

        verify(transitionService).transition(eq(run), eq(RunStatus.SCHEDULED), anyString());
        verify(jobLauncher, times(1)).launchJob(run, tenant);
        verify(transitionService).assignJob(run.getId(), "job-1", "tenant-acme");
    }
}

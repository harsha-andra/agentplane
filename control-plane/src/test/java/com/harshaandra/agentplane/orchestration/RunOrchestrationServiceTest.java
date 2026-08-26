package com.harshaandra.agentplane.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import com.harshaandra.agentplane.stream.RunStreamProducer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunOrchestrationServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private RunStreamProducer streamProducer;
    @Mock
    private RunTransitionService transitionService;
    @Mock
    private JobLauncher jobLauncher;

    private RunOrchestrationService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new RunOrchestrationService(
                tenantRepository, agentRunRepository, auditEventRepository, streamProducer, transitionService, jobLauncher);
        tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 2);
    }

    private RunSubmission submission(String idempotencyKey) {
        return new RunSubmission(tenant.getId(), "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", idempotencyKey);
    }

    @Test
    void submitRunThrowsWhenTenantMissing() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitRun(submission(null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void submitRunReturnsExistingRunOnIdempotentReplay() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        AgentRun existing = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "same-key");
        when(agentRunRepository.findByIdempotencyKey("same-key")).thenReturn(Optional.of(existing));

        AgentRun result = service.submitRun(submission("same-key"));

        assertThat(result).isSameAs(existing);
        verify(agentRunRepository, never()).save(any());
        verify(streamProducer, never()).publish(any(), any(), anyInt());
    }

    @Test
    void submitRunThrowsWhenTenantAtCapacity() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(agentRunRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(agentRunRepository.countByTenantIdAndStatusIn(eq(tenant.getId()), any()))
                .thenReturn((long) tenant.getMaxConcurrentRuns());

        assertThatThrownBy(() -> service.submitRun(submission(null)))
                .isInstanceOf(TenantCapacityExceededException.class);
    }

    @Test
    void submitRunPersistsAuditsAndPublishesOnHappyPath() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        when(agentRunRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(agentRunRepository.countByTenantIdAndStatusIn(eq(tenant.getId()), any())).thenReturn(0L);
        when(agentRunRepository.save(any(AgentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentRun result = service.submitRun(submission(null));

        assertThat(result.getStatus()).isEqualTo(RunStatus.PENDING);
        assertThat(result.getTenant()).isEqualTo(tenant);
        verify(auditEventRepository).save(any());
        verify(streamProducer).publish(eq(result.getId()), eq(result.getIdempotencyKey()), eq(1));
    }

    @Test
    void cancelRunThrowsWhenRunMissing() {
        UUID runId = UUID.randomUUID();
        when(agentRunRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelRun(runId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cancelRunThrowsWhenAlreadyTerminal() {
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key");
        run.applyStatus(RunStatus.RUNNING);
        run.applyStatus(RunStatus.SUCCEEDED);
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.cancelRun(run.getId())).isInstanceOf(InvalidRunTransitionException.class);
    }

    @Test
    void cancelRunSkipsJobLauncherWhenStillPending() {
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key");
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(transitionService.transition(eq(run), eq(RunStatus.CANCELLED), any())).thenReturn(run);

        service.cancelRun(run.getId());

        verify(jobLauncher, never()).cancelJob(any());
        verify(transitionService).transition(run, RunStatus.CANCELLED, "cancelled by user request");
    }

    @Test
    void cancelRunCallsJobLauncherWhenRunning() {
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key");
        run.applyStatus(RunStatus.RUNNING);
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(transitionService.transition(eq(run), eq(RunStatus.CANCELLED), any())).thenReturn(run);

        service.cancelRun(run.getId());

        verify(jobLauncher, times(1)).cancelJob(run);
    }
}

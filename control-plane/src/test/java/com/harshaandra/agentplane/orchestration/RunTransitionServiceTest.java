package com.harshaandra.agentplane.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.sse.RunEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunTransitionServiceTest {

    @Mock
    private AgentRunRepository agentRunRepository;
    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private RunEventPublisher runEventPublisher;

    private RunTransitionService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new RunTransitionService(
                agentRunRepository, auditEventRepository, new RunStateMachine(), runEventPublisher,
                new SimpleMeterRegistry());
        tenant = new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
    }

    private AgentRun newRun() {
        return new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key-" + java.util.UUID.randomUUID());
    }

    @Test
    void validTransitionPersistsAuditsAndBroadcasts() {
        AgentRun run = newRun();
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun result = service.transition(run, RunStatus.SCHEDULED, "picked up");

        assertThat(result.getStatus()).isEqualTo(RunStatus.SCHEDULED);
        verify(agentRunRepository).save(run);
        verify(auditEventRepository).save(any());
        verify(runEventPublisher).publishStatusChange(run, "picked up");
    }

    @Test
    void invalidTransitionThrowsAndDoesNotPersist() {
        AgentRun run = newRun();
        run.applyStatus(RunStatus.RUNNING);
        run.applyStatus(RunStatus.SUCCEEDED);

        assertThatThrownBy(() -> service.transition(run, RunStatus.RUNNING, "nope"))
                .isInstanceOf(InvalidRunTransitionException.class);

        verify(agentRunRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void sameStatusTransitionIsANoOp() {
        AgentRun run = newRun();

        AgentRun result = service.transition(run, RunStatus.PENDING, "still pending");

        assertThat(result).isSameAs(run);
        verify(agentRunRepository, org.mockito.Mockito.never()).save(any());
        verify(runEventPublisher, org.mockito.Mockito.never()).publishStatusChange(any(), any());
    }

    @Test
    void transitionByIdLoadsRunFirst() {
        AgentRun run = newRun();
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentRun result = service.transition(run.getId(), RunStatus.SCHEDULED, "msg");

        assertThat(result.getStatus()).isEqualTo(RunStatus.SCHEDULED);
    }

    @Test
    void updatePodStatusUpdatesWithoutStatusChange() {
        AgentRun run = newRun();
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(agentRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePodStatus(run.getId(), "Running", null, 1, "node-1");

        assertThat(run.getPodPhase()).isEqualTo("Running");
        assertThat(run.getRestartCount()).isEqualTo(1);
        assertThat(run.getNodeName()).isEqualTo("node-1");
    }
}

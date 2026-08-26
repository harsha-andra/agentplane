package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.AuditEvent;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import com.harshaandra.agentplane.stream.RunStreamProducer;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles run submission and cancellation: persistence + audit + enqueue for submission, and
 * state transition + best-effort Kubernetes cleanup for cancellation. Actually launching the
 * Kubernetes Job happens later, asynchronously, when {@code stream.RunStreamConsumer} dequeues
 * the message this class publishes - see {@link RunLaunchProcessor}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunOrchestrationService {

    private static final List<RunStatus> ACTIVE_STATUSES =
            List.of(RunStatus.PENDING, RunStatus.SCHEDULED, RunStatus.RUNNING);

    private final TenantRepository tenantRepository;
    private final AgentRunRepository agentRunRepository;
    private final AuditEventRepository auditEventRepository;
    private final RunStreamProducer streamProducer;
    private final RunTransitionService transitionService;
    private final JobLauncher jobLauncher;

    @Transactional
    public AgentRun submitRun(RunSubmission submission) {
        Tenant tenant = tenantRepository.findById(submission.tenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + submission.tenantId()));

        String idempotencyKey = submission.idempotencyKey() != null && !submission.idempotencyKey().isBlank()
                ? submission.idempotencyKey()
                : UUID.randomUUID().toString();

        var existing = agentRunRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay of run submission with key {} - returning existing run {}",
                    idempotencyKey, existing.get().getId());
            return existing.get();
        }

        long activeRuns = agentRunRepository.countByTenantIdAndStatusIn(tenant.getId(), ACTIVE_STATUSES);
        if (activeRuns >= tenant.getMaxConcurrentRuns()) {
            throw new TenantCapacityExceededException(tenant.getId(), tenant.getMaxConcurrentRuns());
        }

        AgentRun run = new AgentRun(
                tenant,
                submission.agentName(),
                submission.image(),
                submission.prompt(),
                submission.model(),
                submission.maxSteps(),
                submission.timeoutSeconds(),
                submission.env(),
                submission.resourceCpu(),
                submission.resourceMemory(),
                idempotencyKey);
        AgentRun saved = agentRunRepository.save(run);

        auditEventRepository.save(AuditEvent.of(tenant.getId(), saved.getId(), "RUN_SUBMITTED",
                "agent=" + submission.agentName() + " image=" + submission.image()));

        streamProducer.publish(saved.getId(), idempotencyKey, saved.getAttempt());
        log.info("Run {} submitted for tenant {} and queued", saved.getId(), tenant.getSlug());
        return saved;
    }

    @Transactional
    public AgentRun cancelRun(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Run not found: " + runId));

        if (run.getStatus().isTerminal()) {
            throw new InvalidRunTransitionException(run.getStatus(), RunStatus.CANCELLED);
        }

        if (run.getStatus() == RunStatus.SCHEDULED || run.getStatus() == RunStatus.RUNNING) {
            jobLauncher.cancelJob(run);
        }
        return transitionService.transition(run, RunStatus.CANCELLED, "cancelled by user request");
    }
}

package com.harshaandra.agentplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single execution of an agent workload as a Kubernetes Job.
 *
 * <p>Lives in Postgres (not Mongo) because it needs joins to {@link Tenant}, transactional
 * state transitions guarded by {@link #version} (optimistic locking - many actors touch a run
 * concurrently: the API handler, the pod-status watcher, and the stream consumer), and it is
 * queried relationally (by tenant, status, time range) for the runs list and dashboards.
 * The unstructured, high-volume log/tool-call output produced *by* the run lives in MongoDB as
 * {@code trace.RunTrace} instead - see that class for the other half of the story.
 */
@Entity
@Table(name = "agent_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentRun {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "agent_name", nullable = false, updatable = false)
    private String agentName;

    @Column(nullable = false, updatable = false)
    private String image;

    @Column(nullable = false, updatable = false, length = 16_000)
    private String prompt;

    @Column(nullable = false, updatable = false)
    private String model;

    @Column(name = "max_steps", nullable = false, updatable = false)
    private int maxSteps;

    @Column(name = "timeout_seconds", nullable = false, updatable = false)
    private int timeoutSeconds;

    /**
     * Mapped with {@code @JdbcTypeCode(SqlTypes.JSON)} and no explicit {@code columnDefinition}
     * so each dialect picks its own valid DDL type (Postgres -&gt; {@code jsonb}, matching the
     * Flyway migration; H2, used only by the context-load smoke test -&gt; its native JSON type)
     * instead of hard-coding a Postgres-only column definition that would break portability.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, String> env = new HashMap<>();

    @Column(name = "resource_cpu", nullable = false, updatable = false)
    private String resourceCpu;

    @Column(name = "resource_memory", nullable = false, updatable = false)
    private String resourceMemory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "k8s_job_name")
    private String k8sJobName;

    @Column
    private String namespace;

    @Column(nullable = false)
    private int attempt = 1;

    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false)
    private String idempotencyKey;

    @Column(name = "pod_phase")
    private String podPhase;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "restart_count", nullable = false)
    private int restartCount;

    @Column(name = "node_name")
    private String nodeName;

    @Column(length = 4_000)
    private String message;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "token_prompt")
    private Integer tokenPrompt;

    @Column(name = "token_completion")
    private Integer tokenCompletion;

    @Column(name = "token_total")
    private Integer tokenTotal;

    @Column(name = "cost_usd", precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Version
    @Column(nullable = false)
    private long version;

    public AgentRun(
            Tenant tenant,
            String agentName,
            String image,
            String prompt,
            String model,
            int maxSteps,
            int timeoutSeconds,
            Map<String, String> env,
            String resourceCpu,
            String resourceMemory,
            String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.tenant = tenant;
        this.agentName = agentName;
        this.image = image;
        this.prompt = prompt;
        this.model = model;
        this.maxSteps = maxSteps;
        this.timeoutSeconds = timeoutSeconds;
        this.env = env == null ? new HashMap<>() : new HashMap<>(env);
        this.resourceCpu = resourceCpu;
        this.resourceMemory = resourceMemory;
        this.idempotencyKey = idempotencyKey;
        this.status = RunStatus.PENDING;
        this.createdAt = Instant.now();
        this.attempt = 1;
        this.restartCount = 0;
        this.stepCount = 0;
    }

    /** Applies a validated status transition. Callers must go through {@code RunStateMachine}. */
    public void applyStatus(RunStatus newStatus) {
        this.status = newStatus;
        Instant now = Instant.now();
        if (newStatus == RunStatus.RUNNING && this.startedAt == null) {
            this.startedAt = now;
        }
        if (newStatus.isTerminal()) {
            this.finishedAt = now;
        }
    }

    public void assignJob(String k8sJobName, String namespace) {
        this.k8sJobName = k8sJobName;
        this.namespace = namespace;
    }

    public void updatePodStatus(String podPhase, Integer exitCode, int restartCount, String nodeName) {
        this.podPhase = podPhase;
        this.exitCode = exitCode;
        this.restartCount = restartCount;
        this.nodeName = nodeName;
    }

    public void appendMessage(String message) {
        this.message = message;
    }

    public void recordUsage(int stepCount, Integer tokenPrompt, Integer tokenCompletion, Integer tokenTotal, BigDecimal costUsd) {
        this.stepCount = stepCount;
        this.tokenPrompt = tokenPrompt;
        this.tokenCompletion = tokenCompletion;
        this.tokenTotal = tokenTotal;
        this.costUsd = costUsd;
    }

    public void incrementAttempt() {
        this.attempt++;
    }

    public Long durationMs() {
        if (startedAt == null) {
            return null;
        }
        Instant end = finishedAt != null ? finishedAt : Instant.now();
        return end.toEpochMilli() - startedAt.toEpochMilli();
    }
}

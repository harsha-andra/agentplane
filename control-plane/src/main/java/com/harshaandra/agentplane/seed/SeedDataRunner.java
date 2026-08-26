package com.harshaandra.agentplane.seed;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.AuditEvent;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import com.harshaandra.agentplane.trace.RunTrace;
import com.harshaandra.agentplane.trace.TraceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds ~5 tenants, ~80 runs and matching traces so a fresh clone is immediately explorable.
 * Activate with the {@code seed} Spring profile (e.g. alongside {@code local}):
 * {@code SPRING_PROFILES_ACTIVE=local,seed}.
 *
 * <p>Bypasses the normal submission path entirely (no stream publish, no JobLauncher call) -
 * this is data fixture setup, not a simulation of the real run lifecycle. {@link JdbcTemplate} is
 * used only to backdate {@code created_at/started_at/finished_at} to a spread of realistic
 * historical timestamps, since {@link AgentRun} intentionally exposes no public setters for those
 * once a run has left {@code PENDING}.
 */
@Component
@Profile("seed")
@RequiredArgsConstructor
@Slf4j
public class SeedDataRunner implements CommandLineRunner {

    private static final Random RANDOM = new Random(42);

    private static final String[] AGENT_NAMES = {
            "research-agent", "code-reviewer", "support-bot", "data-analyst", "email-drafter"
    };
    private static final String[] MODELS = {"gpt-4o-mini", "claude-3-7-sonnet", "llama-3.1-70b"};
    private static final String[] TOOLS = {"web_search", "code_exec", "file_read", "sql_query", "vector_search"};
    private static final String[] TAGS = {"retry", "timeout", "rate_limited", "invalid_input"};
    private static final RunStatus[] STATUS_POOL = {
            RunStatus.SUCCEEDED, RunStatus.SUCCEEDED, RunStatus.SUCCEEDED, RunStatus.SUCCEEDED,
            RunStatus.FAILED, RunStatus.FAILED,
            RunStatus.CANCELLED, RunStatus.TIMED_OUT,
            RunStatus.RUNNING, RunStatus.PENDING
    };

    private final TenantRepository tenantRepository;
    private final AgentRunRepository agentRunRepository;
    private final AuditEventRepository auditEventRepository;
    private final TraceRepository traceRepository;
    private final JdbcTemplate jdbcTemplate;

    /** Latches once Mongo has failed, so the seed does not pay the driver timeout per run. */
    private final AtomicBoolean mongoUnavailable = new AtomicBoolean(false);

    /**
     * Deliberately NOT {@code @Transactional}.
     *
     * <p>Seeding writes to two stores. Wrapping both in one transaction looks tidy and is a trap:
     * {@code SimpleMongoRepository.save} is itself annotated {@code @Transactional}, so with only
     * a JPA transaction manager present the Mongo write <em>joins the Postgres transaction</em>.
     * When Mongo is unreachable the write fails, the interceptor marks that transaction
     * rollback-only, and every relational row seeded so far is discarded at commit — even though
     * the exception was caught and handled here. The symptom is the worst kind: a clean log, a
     * successful startup, and an empty database.
     *
     * <p>The deeper point is that a Mongo write cannot participate in a Postgres transaction in
     * any meaningful sense — it cannot be rolled back with it. Pretending otherwise buys nothing
     * and costs the relational data. Each repository call is therefore its own transaction, and
     * trace seeding is free to fail on its own.
     */
    @Override
    public void run(String... args) {
        if (tenantRepository.count() > 0) {
            log.info("Seed data already present ({} tenants) - skipping", tenantRepository.count());
            return;
        }

        List<Tenant> tenants = List.of(
                new Tenant("Acme Corp", "acme", "tenant-acme", "8", "16Gi", 5),
                new Tenant("Globex", "globex", "tenant-globex", "4", "8Gi", 3),
                new Tenant("Initech", "initech", "tenant-initech", "16", "32Gi", 8),
                new Tenant("Umbrella", "umbrella", "tenant-umbrella", "2", "4Gi", 2),
                new Tenant("Soylent", "soylent", "tenant-soylent", "8", "16Gi", 4));

        for (Tenant tenant : tenants) {
            tenant.setProvisioningStatus(Tenant.TenantProvisioningStatus.PROVISIONED);
        }
        tenantRepository.saveAll(tenants);

        int totalRuns = 0;
        for (Tenant tenant : tenants) {
            int runsForTenant = 14 + RANDOM.nextInt(4); // ~14-17 each, ~80 total across 5 tenants
            for (int i = 0; i < runsForTenant; i++) {
                seedRun(tenant);
                totalRuns++;
            }
        }
        log.info("Seeded {} tenants and {} runs (profile=seed)", tenants.size(), totalRuns);
    }

    private void seedRun(Tenant tenant) {
        String agentName = pick(AGENT_NAMES);
        RunStatus status = pick(STATUS_POOL);

        AgentRun run = new AgentRun(
                tenant,
                agentName,
                "ghcr.io/agentplane/agent-runtime:1.4.0",
                "Summarize the latest support tickets and draft a response",
                pick(MODELS),
                5 + RANDOM.nextInt(20),
                300,
                Map.of("LOG_LEVEL", "info"),
                "500m",
                "512Mi",
                UUID.randomUUID().toString());
        run = agentRunRepository.save(run);

        Instant createdAt = Instant.now().minus(RANDOM.nextInt(7 * 24 * 60), ChronoUnit.MINUTES);
        Instant startedAt = null;
        Instant finishedAt = null;

        if (status != RunStatus.PENDING) {
            startedAt = createdAt.plusSeconds(2 + RANDOM.nextInt(8));
            run.assignJob("agentplane-run-" + run.getId(), tenant.getNamespace());
        }
        if (status.isTerminal()) {
            finishedAt = startedAt.plusSeconds(10 + RANDOM.nextInt(240));
            int prompt = 200 + RANDOM.nextInt(2000);
            int completion = 50 + RANDOM.nextInt(800);
            BigDecimal cost = BigDecimal.valueOf((prompt + completion) * 0.000002)
                    .setScale(6, RoundingMode.HALF_UP);
            run.recordUsage(3 + RANDOM.nextInt(10), prompt, completion, prompt + completion, cost);
            run.updatePodStatus(
                    status == RunStatus.SUCCEEDED ? "Succeeded" : "Failed",
                    status == RunStatus.SUCCEEDED ? 0 : 1,
                    RANDOM.nextInt(2),
                    "node-" + (1 + RANDOM.nextInt(3)));
        }
        run.applyStatus(status);
        run = agentRunRepository.save(run);

        backdateTimestamps(run.getId(), createdAt, startedAt, finishedAt);

        auditEventRepository.save(AuditEvent.of(tenant.getId(), run.getId(), "RUN_SUBMITTED",
                "agent=" + agentName + " (seed)"));
        if (status != RunStatus.PENDING) {
            auditEventRepository.save(AuditEvent.of(tenant.getId(), run.getId(), "RUN_" + status,
                    "status PENDING -> " + status + " (seed)"));
        }

        // Traces live in MongoDB; everything above is relational. The Postgres side is what makes
        // the console usable — traces enrich it. If Mongo is unreachable, say so once and carry
        // on: a demo-data loader must never be the reason the application refuses to start.
        //
        // The flag short-circuits the *attempt*, not just the logging. The Mongo driver's server
        // selection timeout is 30s, so retrying once per run would turn an 80-run seed into a
        // 40-minute startup — technically working, practically indistinguishable from a hang.
        if (mongoUnavailable.get()) {
            return;
        }
        try {
            seedTraces(run.getId(), startedAt != null ? startedAt : createdAt, status);
        } catch (DataAccessException e) {
            mongoUnavailable.set(true);
            log.warn("Skipping trace seeding — MongoDB is not reachable ({}). Tenants, runs and "
                            + "the audit trail are still seeded; trace views and the tool-latency "
                            + "aggregation stay empty until Mongo is available.",
                    e.getMostSpecificCause().getMessage());
        }
    }

    private void seedTraces(UUID runId, Instant baseTime, RunStatus status) {
        int traceCount = 2 + RANDOM.nextInt(4);
        for (int seq = 1; seq <= traceCount; seq++) {
            boolean isError = status == RunStatus.FAILED && seq == traceCount;
            long latency = 50 + RANDOM.nextInt(1500);
            Map<String, Object> payload = isError
                    ? Map.of("tags", List.of(pick(TAGS)), "attempt", seq)
                    : Map.of("resultSize", RANDOM.nextInt(500), "attempt", seq);
            RunTrace trace = new RunTrace(
                    runId,
                    seq,
                    RunTrace.TraceType.TOOL_CALL,
                    pick(TOOLS),
                    baseTime.plusSeconds(seq * 3L),
                    latency,
                    isError ? RunTrace.TraceStatus.ERROR : RunTrace.TraceStatus.SUCCESS,
                    payload,
                    isError ? "tool invocation failed" : null);
            traceRepository.save(trace);
        }
    }

    private void backdateTimestamps(UUID runId, Instant createdAt, Instant startedAt, Instant finishedAt) {
        jdbcTemplate.update(
                "update agent_runs set created_at = ?, started_at = ?, finished_at = ? where id = ?",
                Timestamp.from(createdAt),
                startedAt != null ? Timestamp.from(startedAt) : null,
                finishedAt != null ? Timestamp.from(finishedAt) : null,
                runId);
    }

    private static <T> T pick(T[] pool) {
        return pool[RANDOM.nextInt(pool.length)];
    }
}

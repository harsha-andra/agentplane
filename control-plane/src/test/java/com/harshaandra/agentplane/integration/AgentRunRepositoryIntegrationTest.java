package com.harshaandra.agentplane.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AgentRunSpecifications;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises the real Postgres schema (Flyway migration + Hibernate ddl-auto=validate) via
 * Testcontainers - see README.md for how to run this ({@code mvn verify -Pintegration}, needs
 * Docker). Excluded from the default {@code mvn verify} run.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AgentRunRepositoryIntegrationTest {

    /**
     * A container by default - that is what CI runs. If AGENTPLANE_TEST_JDBC_URL is set, that
     * database is used instead, so these tests can also be run on a machine where the container
     * runtime is unavailable or image pulls are blocked.
     *
     * The lifecycle is managed here rather than by @Testcontainers/@Container so the container is
     * only created when it is actually going to be used.
     */
    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String externalUrl = System.getenv("AGENTPLANE_TEST_JDBC_URL");

        if (externalUrl != null && !externalUrl.isBlank()) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username",
                    () -> envOrDefault("AGENTPLANE_TEST_DB_USER", "agentplane"));
            registry.add("spring.datasource.password",
                    () -> envOrDefault("AGENTPLANE_TEST_DB_PASSWORD", "agentplane"));
            return;
        }

        if (postgres == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Tenant newTenant(String slug) {
        return tenantRepository.save(new Tenant(slug, slug, "tenant-" + slug, "4", "8Gi", 5));
    }

    private AgentRun newRun(Tenant tenant, String idempotencyKey) {
        return new AgentRun(tenant, "agent", "img:1", "prompt", "model", 5, 60,
                Map.of("K", "V"), "500m", "512Mi", idempotencyKey);
    }

    @Test
    void savesAndReloadsARunWithItsJsonEnvColumn() {
        Tenant tenant = newTenant("acme");
        AgentRun saved = agentRunRepository.save(newRun(tenant, "key-1"));
        entityManager.flush();
        entityManager.clear();

        AgentRun reloaded = agentRunRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEnv()).containsEntry("K", "V");
        assertThat(reloaded.getTenant().getSlug()).isEqualTo("acme");
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void idempotencyKeyIsUniqueAtTheDatabaseLevel() {
        Tenant tenant = newTenant("globex");
        agentRunRepository.saveAndFlush(newRun(tenant, "dup-key"));

        assertThatThrownBy(() -> agentRunRepository.saveAndFlush(newRun(tenant, "dup-key")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void specificationFiltersByStatusTenantAndFreeText() {
        Tenant tenant = newTenant("initech");
        AgentRun r1 = agentRunRepository.save(newRun(tenant, "spec-1"));
        r1.applyStatus(RunStatus.RUNNING);
        agentRunRepository.save(r1);
        agentRunRepository.save(newRun(tenant, "spec-2"));

        var page = agentRunRepository.findAll(
                AgentRunSpecifications.withFilters(RunStatus.RUNNING, tenant.getId(), null),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getIdempotencyKey()).isEqualTo("spec-1");

        var byText = agentRunRepository.findAll(
                AgentRunSpecifications.withFilters(null, null, "agent"), PageRequest.of(0, 10));
        assertThat(byText.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    /**
     * Proves the guarantee that matters: the UPDATE Hibernate issues carries the version in its
     * WHERE clause, so a write based on a stale read matches zero rows and is rejected rather
     * than silently overwriting whatever landed in between.
     *
     * The competing write is issued with plain JDBC, behind Hibernate's back, because loading
     * the same row twice does NOT give you two independent copies. A persistence context holds
     * exactly one instance per identifier, so a second findById returns the same object and a
     * later merge of a detached copy is applied *onto that instance* - the two "copies" are one,
     * and no conflict is possible. The first version of this test did exactly that and asserted
     * an exception that could never be thrown.
     */
    @Test
    void optimisticLockingRejectsAConcurrentStaleUpdate() {
        Tenant tenant = newTenant("umbrella");
        AgentRun saved = agentRunRepository.saveAndFlush(newRun(tenant, "opt-lock"));
        entityManager.clear();

        AgentRun ours = agentRunRepository.findById(saved.getId()).orElseThrow();
        long versionWeRead = ours.getVersion();

        // Someone else commits a change to this row while we hold our copy.
        int rowsUpdated = jdbcTemplate.update(
                "UPDATE agent_runs SET version = version + 1 WHERE id = ?", saved.getId());
        assertThat(rowsUpdated).isEqualTo(1);

        // Our write now targets a version that no longer exists:
        //   UPDATE agent_runs SET ... WHERE id = ? AND version = <versionWeRead>
        // matches nothing, and Hibernate turns "zero rows updated" into a lock failure rather
        // than a silent no-op.
        ours.applyStatus(RunStatus.CANCELLED);
        assertThatThrownBy(() -> agentRunRepository.saveAndFlush(ours))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(versionWeRead).isZero();
    }
}

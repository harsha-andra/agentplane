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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the real Postgres schema (Flyway migration + Hibernate ddl-auto=validate) via
 * Testcontainers - see README.md for how to run this ({@code mvn verify -Pintegration}, needs
 * Docker). Excluded from the default {@code mvn verify} run.
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AgentRunRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private AgentRunRepository agentRunRepository;
    @Autowired
    private EntityManager entityManager;

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

    @Test
    void optimisticLockingRejectsAConcurrentStaleUpdate() {
        Tenant tenant = newTenant("umbrella");
        AgentRun saved = agentRunRepository.saveAndFlush(newRun(tenant, "opt-lock"));
        entityManager.clear();

        AgentRun copyA = agentRunRepository.findById(saved.getId()).orElseThrow();
        entityManager.clear();
        AgentRun copyB = agentRunRepository.findById(saved.getId()).orElseThrow();

        copyA.applyStatus(RunStatus.SCHEDULED);
        agentRunRepository.saveAndFlush(copyA);

        copyB.applyStatus(RunStatus.CANCELLED);
        assertThatThrownBy(() -> agentRunRepository.saveAndFlush(copyB))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }
}

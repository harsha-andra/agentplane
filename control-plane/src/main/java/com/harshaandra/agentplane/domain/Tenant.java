package com.harshaandra.agentplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A tenant (customer) of the control plane. Every tenant is provisioned its own Kubernetes
 * namespace with a {@code ResourceQuota} + {@code LimitRange} so that one customer's workloads
 * cannot starve another's - see {@code orchestration.JobLauncher#provisionTenantNamespace}.
 *
 * <p>Lives in Postgres because tenant records participate in joins/foreign keys with
 * {@link AgentRun} and need transactional guarantees around quota bookkeeping.
 */
@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tenant {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false, unique = true)
    private String namespace;

    @Column(name = "quota_cpu", nullable = false)
    private String quotaCpu;

    @Column(name = "quota_memory", nullable = false)
    private String quotaMemory;

    @Column(name = "max_concurrent_runs", nullable = false)
    private int maxConcurrentRuns;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "provisioning_status", nullable = false)
    private TenantProvisioningStatus provisioningStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Tenant(
            String name,
            String slug,
            String namespace,
            String quotaCpu,
            String quotaMemory,
            int maxConcurrentRuns) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.slug = slug;
        this.namespace = namespace;
        this.quotaCpu = quotaCpu;
        this.quotaMemory = quotaMemory;
        this.maxConcurrentRuns = maxConcurrentRuns;
        this.provisioningStatus = TenantProvisioningStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public enum TenantProvisioningStatus {
        PENDING,
        PROVISIONED,
        FAILED
    }
}

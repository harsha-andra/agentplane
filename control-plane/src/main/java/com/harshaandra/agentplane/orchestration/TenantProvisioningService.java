package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AuditEvent;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates tenants and provisions their Kubernetes namespace + quota + limit range - "each
 * customer gets their own namespace with its own CPU and memory limits, so one customer cannot
 * starve another".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantProvisioningService {

    private final TenantRepository tenantRepository;
    private final AuditEventRepository auditEventRepository;
    private final JobLauncher jobLauncher;

    @Transactional
    public Tenant createTenant(String name, String slug, String quotaCpu, String quotaMemory, int maxConcurrentRuns) {
        if (tenantRepository.existsBySlug(slug)) {
            throw new DuplicateSlugException(slug);
        }

        Tenant tenant = new Tenant(name, slug, "tenant-" + slug, quotaCpu, quotaMemory, maxConcurrentRuns);
        Tenant saved = tenantRepository.save(tenant);

        try {
            jobLauncher.provisionTenantNamespace(saved);
            saved.setProvisioningStatus(Tenant.TenantProvisioningStatus.PROVISIONED);
        } catch (Exception e) {
            log.error("Failed to provision namespace for tenant {}: {}", slug, e.getMessage(), e);
            saved.setProvisioningStatus(Tenant.TenantProvisioningStatus.FAILED);
        }
        tenantRepository.save(saved);

        auditEventRepository.save(AuditEvent.of(saved.getId(), null, "TENANT_CREATED",
                "name=" + name + " slug=" + slug + " namespace=" + saved.getNamespace()));
        return saved;
    }
}

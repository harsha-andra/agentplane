package com.harshaandra.agentplane.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AuditEventRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TenantProvisioningServiceTest {

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private JobLauncher jobLauncher;

    private TenantProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new TenantProvisioningService(tenantRepository, auditEventRepository, jobLauncher);
    }

    @Test
    void rejectsDuplicateSlug() {
        when(tenantRepository.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.createTenant("Acme", "acme", "4", "8Gi", 5))
                .isInstanceOf(DuplicateSlugException.class);

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void marksProvisionedOnSuccess() {
        when(tenantRepository.existsBySlug("acme")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant tenant = service.createTenant("Acme", "acme", "4", "8Gi", 5);

        assertThat(tenant.getProvisioningStatus()).isEqualTo(Tenant.TenantProvisioningStatus.PROVISIONED);
        assertThat(tenant.getNamespace()).isEqualTo("tenant-acme");
        verify(jobLauncher).provisionTenantNamespace(tenant);
        verify(auditEventRepository).save(any());
        verify(tenantRepository, times(2)).save(any(Tenant.class));
    }

    @Test
    void marksFailedWhenProvisioningThrows() {
        when(tenantRepository.existsBySlug("acme")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("cluster unreachable")).when(jobLauncher).provisionTenantNamespace(any());

        Tenant tenant = service.createTenant("Acme", "acme", "4", "8Gi", 5);

        assertThat(tenant.getProvisioningStatus()).isEqualTo(Tenant.TenantProvisioningStatus.FAILED);
    }
}

package com.harshaandra.agentplane.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.api.dto.TenantDto;
import com.harshaandra.agentplane.domain.Tenant;
import org.junit.jupiter.api.Test;

class TenantMapperTest {

    private final TenantMapper mapper = new TenantMapperImpl();

    @Test
    void mapsTenantAndInjectsActiveRunsParameter() {
        Tenant tenant = new Tenant("Acme Corp", "acme", "tenant-acme", "4", "8Gi", 5);

        TenantDto dto = mapper.toDto(tenant, 3);

        assertThat(dto.id()).isEqualTo(tenant.getId());
        assertThat(dto.name()).isEqualTo("Acme Corp");
        assertThat(dto.slug()).isEqualTo("acme");
        assertThat(dto.namespace()).isEqualTo("tenant-acme");
        assertThat(dto.quotaCpu()).isEqualTo("4");
        assertThat(dto.quotaMemory()).isEqualTo("8Gi");
        assertThat(dto.maxConcurrentRuns()).isEqualTo(5);
        assertThat(dto.activeRuns()).isEqualTo(3);
        assertThat(dto.createdAt()).isEqualTo(tenant.getCreatedAt());
    }
}

package com.harshaandra.agentplane.api.controller;

import com.harshaandra.agentplane.api.dto.TenantCreateRequest;
import com.harshaandra.agentplane.api.dto.TenantDto;
import com.harshaandra.agentplane.api.mapper.TenantMapper;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.TenantRepository;
import com.harshaandra.agentplane.orchestration.TenantProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Tenants", description = "Customer accounts, each with their own namespace and quota")
public class TenantController {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.PENDING, RunStatus.SCHEDULED, RunStatus.RUNNING);

    private final TenantRepository tenantRepository;
    private final AgentRunRepository agentRunRepository;
    private final TenantProvisioningService provisioningService;
    private final TenantMapper tenantMapper;

    @GetMapping
    @Operation(summary = "List tenants")
    public List<TenantDto> list() {
        return tenantRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(tenant -> tenantMapper.toDto(tenant, activeRuns(tenant)))
                .toList();
    }

    @PostMapping
    @Operation(summary = "Create a tenant", description = "Provisions a Kubernetes namespace + ResourceQuota + LimitRange for the tenant")
    public ResponseEntity<TenantDto> create(@Valid @RequestBody TenantCreateRequest request) {
        Tenant tenant = provisioningService.createTenant(
                request.name(), request.slug(), request.quotaCpu(), request.quotaMemory(), request.maxConcurrentRuns());
        TenantDto dto = tenantMapper.toDto(tenant, 0);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenant.getId())).body(dto);
    }

    private long activeRuns(Tenant tenant) {
        return agentRunRepository.countByTenantIdAndStatusIn(tenant.getId(), ACTIVE);
    }
}

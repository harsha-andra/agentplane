package com.harshaandra.agentplane.api.mapper;

import com.harshaandra.agentplane.api.dto.TenantDto;
import com.harshaandra.agentplane.domain.Tenant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TenantMapper {

    @Mapping(target = "activeRuns", source = "activeRuns")
    TenantDto toDto(Tenant tenant, long activeRuns);
}

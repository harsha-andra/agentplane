package com.harshaandra.agentplane.api.mapper;

import com.harshaandra.agentplane.api.dto.ToolLatencyDto;
import com.harshaandra.agentplane.trace.ToolLatencyStats;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AnalyticsMapper {

    ToolLatencyDto toDto(ToolLatencyStats stats);

    List<ToolLatencyDto> toDtoList(List<ToolLatencyStats> stats);
}

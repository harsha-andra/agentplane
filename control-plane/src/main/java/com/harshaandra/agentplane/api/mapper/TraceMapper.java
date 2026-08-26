package com.harshaandra.agentplane.api.mapper;

import com.harshaandra.agentplane.api.dto.TraceDto;
import com.harshaandra.agentplane.trace.RunTrace;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TraceMapper {

    @Mapping(target = "type", expression = "java(trace.getType() != null ? trace.getType().name() : null)")
    @Mapping(target = "status", expression = "java(trace.getStatus() != null ? trace.getStatus().name() : null)")
    TraceDto toDto(RunTrace trace);

    List<TraceDto> toDtoList(List<RunTrace> traces);
}

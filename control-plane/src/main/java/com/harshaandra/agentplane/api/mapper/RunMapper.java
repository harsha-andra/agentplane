package com.harshaandra.agentplane.api.mapper;

import com.harshaandra.agentplane.api.dto.RunDetail;
import com.harshaandra.agentplane.api.dto.RunSpecRequest;
import com.harshaandra.agentplane.api.dto.RunSpecView;
import com.harshaandra.agentplane.api.dto.RunSummary;
import com.harshaandra.agentplane.api.dto.TokenUsage;
import com.harshaandra.agentplane.domain.AgentRun;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Entity -&gt; DTO mapping for {@link AgentRun}. Entities are never returned from the API directly. */
@Mapper(componentModel = "spring")
public interface RunMapper {

    @Mapping(target = "tenantId", expression = "java(run.getTenant().getId())")
    @Mapping(target = "tenantName", expression = "java(run.getTenant().getName())")
    @Mapping(target = "durationMs", expression = "java(run.durationMs())")
    RunSummary toSummary(AgentRun run);

    List<RunSummary> toSummaryList(List<AgentRun> runs);

    @Mapping(target = "tenantId", expression = "java(run.getTenant().getId())")
    @Mapping(target = "tenantName", expression = "java(run.getTenant().getName())")
    @Mapping(target = "durationMs", expression = "java(run.durationMs())")
    @Mapping(target = "spec", expression = "java(toSpecView(run))")
    @Mapping(target = "tokenUsage", expression = "java(toTokenUsage(run))")
    RunDetail toDetail(AgentRun run);

    default RunSpecView toSpecView(AgentRun run) {
        return new RunSpecView(
                run.getAgentName(),
                run.getImage(),
                run.getPrompt(),
                run.getModel(),
                run.getMaxSteps(),
                run.getTimeoutSeconds(),
                run.getEnv(),
                new RunSpecRequest.ResourceSpec(run.getResourceCpu(), run.getResourceMemory()));
    }

    default TokenUsage toTokenUsage(AgentRun run) {
        return new TokenUsage(run.getTokenPrompt(), run.getTokenCompletion(), run.getTokenTotal());
    }
}

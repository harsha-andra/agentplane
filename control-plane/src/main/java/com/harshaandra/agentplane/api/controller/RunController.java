package com.harshaandra.agentplane.api.controller;

import com.harshaandra.agentplane.api.dto.PageResponse;
import com.harshaandra.agentplane.api.dto.RunDetail;
import com.harshaandra.agentplane.api.dto.RunSpecRequest;
import com.harshaandra.agentplane.api.dto.RunSummary;
import com.harshaandra.agentplane.api.dto.TraceDto;
import com.harshaandra.agentplane.api.mapper.RunMapper;
import com.harshaandra.agentplane.api.mapper.TraceMapper;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.domain.repository.AgentRunSpecifications;
import com.harshaandra.agentplane.orchestration.ResourceNotFoundException;
import com.harshaandra.agentplane.orchestration.RunOrchestrationService;
import com.harshaandra.agentplane.orchestration.RunSubmission;
import com.harshaandra.agentplane.sse.SseEmitterRegistry;
import com.harshaandra.agentplane.trace.TraceRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/runs")
@RequiredArgsConstructor
@Tag(name = "Runs", description = "Submit, inspect, cancel and watch agent runs")
public class RunController {

    private final AgentRunRepository agentRunRepository;
    private final TraceRepository traceRepository;
    private final RunOrchestrationService orchestrationService;
    private final RunMapper runMapper;
    private final TraceMapper traceMapper;
    private final SseEmitterRegistry sseEmitterRegistry;

    @GetMapping
    @Operation(summary = "List runs", description = "Paginated, filterable by status/tenant/free-text query")
    public PageResponse<RunSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String q) {
        var pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var spec = AgentRunSpecifications.withFilters(status, tenantId, q);
        var result = agentRunRepository.findAll(spec, pageRequest).map(runMapper::toSummary);
        return PageResponse.of(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get run detail")
    public RunDetail get(@PathVariable UUID id) {
        return runMapper.toDetail(loadRun(id));
    }

    @PostMapping
    @Operation(summary = "Submit a new run", description = "Persists the run as PENDING and enqueues it onto the Redis stream for a worker to launch")
    public ResponseEntity<RunDetail> submit(@Valid @RequestBody RunSpecRequest request) {
        AgentRun run = orchestrationService.submitRun(new RunSubmission(
                request.tenantId(),
                request.agentName(),
                request.image(),
                request.prompt(),
                request.model(),
                request.maxSteps(),
                request.timeoutSeconds(),
                request.env(),
                request.resources().cpu(),
                request.resources().memory(),
                request.idempotencyKey()));
        RunDetail detail = runMapper.toDetail(run);
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.getId())).body(detail);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a run")
    public RunDetail cancel(@PathVariable UUID id) {
        AgentRun run = orchestrationService.cancelRun(id);
        return runMapper.toDetail(run);
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live run event stream (SSE)", description = "Status transitions and log lines for this run")
    public SseEmitter events(@PathVariable UUID id) {
        loadRun(id); // 404 if unknown before opening the stream
        return sseEmitterRegistry.register(id);
    }

    @GetMapping("/{id}/traces")
    @Operation(summary = "Raw execution trace for a run (from MongoDB)")
    public List<TraceDto> traces(@PathVariable UUID id) {
        loadRun(id);
        return traceMapper.toDtoList(traceRepository.findByRunIdOrderBySeqAsc(id));
    }

    private AgentRun loadRun(UUID id) {
        return agentRunRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Run not found: " + id));
    }
}

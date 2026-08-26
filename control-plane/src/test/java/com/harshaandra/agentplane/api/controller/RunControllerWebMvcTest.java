package com.harshaandra.agentplane.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harshaandra.agentplane.api.mapper.RunMapperImpl;
import com.harshaandra.agentplane.api.mapper.TraceMapperImpl;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.Tenant;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import com.harshaandra.agentplane.orchestration.ResourceNotFoundException;
import com.harshaandra.agentplane.orchestration.RunOrchestrationService;
import com.harshaandra.agentplane.sse.SseEmitterRegistry;
import com.harshaandra.agentplane.trace.TraceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Exercises the actual HTTP contract for {@code /api/v1/runs} - status codes, the narrow page
 * shape, and RFC 7807 error bodies - against a real MockMvc dispatch, with collaborators mocked
 * so no database/Mongo/Redis is needed.
 */
@WebMvcTest(RunController.class)
@Import({RunMapperImpl.class, TraceMapperImpl.class})
class RunControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRunRepository agentRunRepository;
    @MockBean
    private TraceRepository traceRepository;
    @MockBean
    private RunOrchestrationService orchestrationService;
    @MockBean
    private SseEmitterRegistry sseEmitterRegistry;

    private Tenant tenant() {
        return new Tenant("Acme", "acme", "tenant-acme", "4", "8Gi", 5);
    }

    @Test
    void submitReturns201WithLocationAndBody() throws Exception {
        Tenant tenant = tenant();
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key1");
        when(orchestrationService.submitRun(any())).thenReturn(run);

        String body = """
                {"tenantId":"%s","agentName":"agent","image":"img","prompt":"do the thing","model":"gpt-4o-mini",
                 "maxSteps":5,"timeoutSeconds":60,"resources":{"cpu":"500m","memory":"512Mi"}}
                """.formatted(tenant.getId());

        mockMvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/runs/" + run.getId()))
                .andExpect(jsonPath("$.id").value(run.getId().toString()))
                .andExpect(jsonPath("$.tenantId").value(tenant.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.spec.agentName").value("agent"));
    }

    @Test
    void submitWithMissingFieldsReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void getUnknownRunReturns404ProblemDetail() throws Exception {
        UUID id = UUID.randomUUID();
        when(agentRunRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/runs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void getKnownRunReturns200WithDetail() throws Exception {
        Tenant tenant = tenant();
        AgentRun run = new AgentRun(tenant, "agent", "img", "prompt", "model", 5, 60,
                Map.of(), "500m", "512Mi", "key2");
        when(agentRunRepository.findById(run.getId())).thenReturn(Optional.of(run));

        mockMvc.perform(get("/api/v1/runs/{id}", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentName").value("agent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsTheNarrowPageShapeNotSpringsDefault() throws Exception {
        when(agentRunRepository.findAll(org.mockito.ArgumentMatchers.<Specification<AgentRun>>any(), any(Pageable.class)))
                .thenReturn((Page<AgentRun>) new PageImpl<AgentRun>(List.of()));

        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.number").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());
    }

    @Test
    void cancelUnknownRunReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orchestrationService.cancelRun(id)).thenThrow(new ResourceNotFoundException("Run not found: " + id));

        mockMvc.perform(post("/api/v1/runs/{id}/cancel", id))
                .andExpect(status().isNotFound());
    }
}

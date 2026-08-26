package com.harshaandra.agentplane.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.api.dto.TraceDto;
import com.harshaandra.agentplane.trace.RunTrace;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceMapperTest {

    private final TraceMapper mapper = new TraceMapperImpl();

    @Test
    void mapsEnumsToStringsAndPreservesPayload() {
        UUID runId = UUID.randomUUID();
        RunTrace trace = new RunTrace(
                runId, 1, RunTrace.TraceType.TOOL_CALL, "web_search", Instant.now(), 123L,
                RunTrace.TraceStatus.ERROR, Map.of("q", "weather"), "timeout");

        TraceDto dto = mapper.toDto(trace);

        assertThat(dto.runId()).isEqualTo(runId);
        assertThat(dto.seq()).isEqualTo(1);
        assertThat(dto.type()).isEqualTo("TOOL_CALL");
        assertThat(dto.toolName()).isEqualTo("web_search");
        assertThat(dto.latencyMs()).isEqualTo(123L);
        assertThat(dto.status()).isEqualTo("ERROR");
        assertThat(dto.payload()).containsEntry("q", "weather");
        assertThat(dto.error()).isEqualTo("timeout");
    }

    @Test
    void toDtoListMapsEachElement() {
        UUID runId = UUID.randomUUID();
        RunTrace t1 = new RunTrace(runId, 1, RunTrace.TraceType.LOG, null, Instant.now(), null,
                RunTrace.TraceStatus.SUCCESS, Map.of(), null);
        RunTrace t2 = new RunTrace(runId, 2, RunTrace.TraceType.LOG, null, Instant.now(), null,
                RunTrace.TraceStatus.SUCCESS, Map.of(), null);

        assertThat(mapper.toDtoList(java.util.List.of(t1, t2))).hasSize(2);
    }
}

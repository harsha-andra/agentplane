package com.harshaandra.agentplane.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.api.dto.ToolLatencyDto;
import com.harshaandra.agentplane.trace.ToolLatencyStats;
import org.junit.jupiter.api.Test;

class AnalyticsMapperTest {

    private final AnalyticsMapper mapper = new AnalyticsMapperImpl();

    @Test
    void mapsAllFieldsByName() {
        ToolLatencyStats stats = new ToolLatencyStats("web_search", 42L, 120.5, 340.0, 0.05);

        ToolLatencyDto dto = mapper.toDto(stats);

        assertThat(dto.toolName()).isEqualTo("web_search");
        assertThat(dto.callCount()).isEqualTo(42L);
        assertThat(dto.avgLatencyMs()).isEqualTo(120.5);
        assertThat(dto.p95LatencyMs()).isEqualTo(340.0);
        assertThat(dto.errorRate()).isEqualTo(0.05);
    }
}

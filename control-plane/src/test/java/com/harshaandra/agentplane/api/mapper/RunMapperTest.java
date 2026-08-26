package com.harshaandra.agentplane.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.harshaandra.agentplane.api.dto.RunDetail;
import com.harshaandra.agentplane.api.dto.RunSummary;
import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.Tenant;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunMapperTest {

    private final RunMapper mapper = new RunMapperImpl();

    @Test
    void mapsSummaryFieldsIncludingComputedOnes() {
        Tenant tenant = new Tenant("Acme Corp", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run = new AgentRun(tenant, "research-agent", "img:1", "do the thing", "gpt-4o-mini",
                10, 300, Map.of("K", "V"), "500m", "512Mi", "idem-1");
        run.applyStatus(RunStatus.RUNNING);
        run.applyStatus(RunStatus.SUCCEEDED);

        RunSummary summary = mapper.toSummary(run);

        assertThat(summary.id()).isEqualTo(run.getId());
        assertThat(summary.tenantId()).isEqualTo(tenant.getId());
        assertThat(summary.tenantName()).isEqualTo("Acme Corp");
        assertThat(summary.agentName()).isEqualTo("research-agent");
        assertThat(summary.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(summary.durationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(summary.idempotencyKey()).isEqualTo("idem-1");
    }

    @Test
    void mapsDetailIncludingSpecAndTokenUsage() {
        Tenant tenant = new Tenant("Acme Corp", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run = new AgentRun(tenant, "research-agent", "img:1", "do the thing", "gpt-4o-mini",
                10, 300, Map.of("K", "V"), "500m", "512Mi", "idem-2");
        run.recordUsage(4, 100, 50, 150, new BigDecimal("0.001200"));
        run.updatePodStatus("Running", null, 0, "node-1");

        RunDetail detail = mapper.toDetail(run);

        assertThat(detail.spec().agentName()).isEqualTo("research-agent");
        assertThat(detail.spec().resources().cpu()).isEqualTo("500m");
        assertThat(detail.spec().resources().memory()).isEqualTo("512Mi");
        assertThat(detail.spec().env()).containsEntry("K", "V");
        assertThat(detail.tokenUsage().prompt()).isEqualTo(100);
        assertThat(detail.tokenUsage().completion()).isEqualTo(50);
        assertThat(detail.tokenUsage().total()).isEqualTo(150);
        assertThat(detail.podPhase()).isEqualTo("Running");
        assertThat(detail.nodeName()).isEqualTo("node-1");
    }

    @Test
    void toSummaryListMapsEachElement() {
        Tenant tenant = new Tenant("Acme Corp", "acme", "tenant-acme", "4", "8Gi", 5);
        AgentRun run1 = new AgentRun(tenant, "a", "img", "p", "m", 1, 10, Map.of(), "1", "1Gi", "k1");
        AgentRun run2 = new AgentRun(tenant, "b", "img", "p", "m", 1, 10, Map.of(), "1", "1Gi", "k2");

        var list = mapper.toSummaryList(java.util.List.of(run1, run2));

        assertThat(list).hasSize(2);
        assertThat(list.get(0).agentName()).isEqualTo("a");
        assertThat(list.get(1).agentName()).isEqualTo("b");
    }
}

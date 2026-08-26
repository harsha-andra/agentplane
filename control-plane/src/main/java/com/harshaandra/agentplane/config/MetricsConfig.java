package com.harshaandra.agentplane.config;

import com.harshaandra.agentplane.domain.RunStatus;
import com.harshaandra.agentplane.domain.repository.AgentRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Component;

/**
 * Custom gauges exposed at {@code /actuator/prometheus}: {@code agentplane_active_runs} (runs
 * currently PENDING/SCHEDULED/RUNNING, across all tenants) and {@code agentplane_stream_depth}
 * (unconsumed length of the Redis run queue). Run duration itself is recorded as a
 * {@code agentplane_run_duration} timer directly in {@code orchestration.RunTransitionService}
 * at the moment a run reaches a terminal state.
 */
@Configuration
public class MetricsConfig {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.PENDING, RunStatus.SCHEDULED, RunStatus.RUNNING);

    @Component
    static class GaugeRegistrar {

        GaugeRegistrar(
                MeterRegistry registry,
                AgentRunRepository agentRunRepository,
                StreamProperties streamProperties,
                ObjectProvider<StreamOperations<String, Object, Object>> streamOperationsProvider) {

            Gauge.builder("agentplane_active_runs", agentRunRepository,
                            repo -> repo.countByStatusIn(ACTIVE))
                    .description("Runs currently PENDING, SCHEDULED or RUNNING")
                    .register(registry);

            Gauge.builder("agentplane_stream_depth", streamOperationsProvider, provider -> {
                        StreamOperations<String, Object, Object> ops = provider.getIfAvailable();
                        if (ops == null) {
                            return 0d;
                        }
                        try {
                            Long size = ops.size(streamProperties.getStreamKey());
                            return size == null ? 0d : size.doubleValue();
                        } catch (Exception e) {
                            return 0d;
                        }
                    })
                    .description("Unconsumed length of the Redis run queue stream")
                    .register(registry);
        }
    }
}

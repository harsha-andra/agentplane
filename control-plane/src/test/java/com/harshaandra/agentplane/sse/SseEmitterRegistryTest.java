package com.harshaandra.agentplane.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    void registerAddsASubscriberAndBroadcastReachesIt() {
        UUID runId = UUID.randomUUID();
        SseEmitter emitter = registry.register(runId);

        assertThat(emitter).isNotNull();
        assertThat(registry.subscriberCount(runId)).isEqualTo(1);

        // Should not throw even though there's no real HTTP response behind this emitter in a
        // unit test - broadcast/heartbeat swallow send failures and clean up instead.
        registry.broadcast(runId, "status", new RunEvent(1, runId, java.time.Instant.now(),
                "INFO", "test", "hello", com.harshaandra.agentplane.domain.RunStatus.RUNNING));
        registry.heartbeat();
    }

    @Test
    void completeRemovesAllSubscribersForThatRun() {
        UUID runId = UUID.randomUUID();
        registry.register(runId);
        registry.register(runId);
        assertThat(registry.subscriberCount(runId)).isEqualTo(2);

        registry.complete(runId);

        assertThat(registry.subscriberCount(runId)).isEqualTo(0);
    }

    @Test
    void broadcastToUnknownRunIsANoOp() {
        registry.broadcast(UUID.randomUUID(), "status", "irrelevant");
    }
}

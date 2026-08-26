package com.harshaandra.agentplane.sse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Keyed registry of live {@link SseEmitter}s, one list per run id (a run can have more than one
 * subscriber - e.g. two browser tabs). Handles cleanup on completion, timeout and error so
 * emitters never leak, and a heartbeat so intermediate proxies don't kill idle connections.
 */
@Component
@Slf4j
public class SseEmitterRegistry {

    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes

    private final Map<UUID, List<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();

    public SseEmitter register(UUID runId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        List<SseEmitter> emitters = emittersByRun.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(ex -> remove(runId, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("run " + runId));
        } catch (IOException e) {
            remove(runId, emitter);
        }
        return emitter;
    }

    public void broadcast(UUID runId, String eventName, Object payload) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(emitters)) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                remove(runId, emitter);
            }
        }
    }

    public void complete(UUID runId) {
        List<SseEmitter> emitters = emittersByRun.remove(runId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
    }

    public void heartbeat() {
        emittersByRun.forEach((runId, emitters) -> {
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    remove(runId, emitter);
                }
            }
        });
    }

    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emittersByRun.remove(runId);
            }
        }
    }

    int subscriberCount(UUID runId) {
        return emittersByRun.getOrDefault(runId, List.of()).size();
    }
}

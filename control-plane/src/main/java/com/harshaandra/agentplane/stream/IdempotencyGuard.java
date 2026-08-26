package com.harshaandra.agentplane.stream;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A distributed "run this exactly once" guard built on Redis {@code SETNX} (via
 * {@code setIfAbsent}, which is atomic). Used by {@link RunStreamConsumer} so that if a worker
 * dies mid-job and another worker picks up the redelivered stream message, the actual
 * side-effecting work (launching the Kubernetes Job) is not repeated - only the bookkeeping
 * (state transitions, acknowledgement) is redone.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyGuard {

    private final StringRedisTemplate redisTemplate;

    /**
     * Atomically claims {@code key} for {@code ttl}. Returns {@code true} the first time it is
     * called for a given key (the caller should proceed), {@code false} on every subsequent call
     * within the TTL (the caller should treat the work as already done/in-progress).
     */
    public boolean tryAcquire(String key, Duration ttl) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(acquired);
    }

    public boolean isAcquired(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public void release(String key) {
        redisTemplate.delete(key);
    }
}

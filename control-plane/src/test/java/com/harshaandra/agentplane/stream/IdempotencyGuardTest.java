package com.harshaandra.agentplane.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(redisTemplate);
    }

    @Test
    void firstAcquireSucceeds() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("run:1"), eq("1"), any(Duration.class))).thenReturn(true);

        boolean acquired = guard.tryAcquire("run:1", Duration.ofSeconds(60));

        assertThat(acquired).isTrue();
        verify(valueOperations).setIfAbsent("run:1", "1", Duration.ofSeconds(60));
    }

    @Test
    void secondAcquireOnSameKeyFails() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("run:1"), eq("1"), any(Duration.class))).thenReturn(false);

        boolean acquired = guard.tryAcquire("run:1", Duration.ofSeconds(60));

        assertThat(acquired).isFalse();
    }

    @Test
    void nullResultFromRedisIsTreatedAsNotAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

        assertThat(guard.tryAcquire("run:2", Duration.ofSeconds(1))).isFalse();
    }
}

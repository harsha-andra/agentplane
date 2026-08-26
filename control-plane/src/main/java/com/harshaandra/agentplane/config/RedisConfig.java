package com.harshaandra.agentplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis is used purely as a message broker here (Streams) plus a distributed idempotency guard
 * (SETNX-style). {@code StringRedisTemplate} and its {@code RedisConnectionFactory} are already
 * provided by Spring Boot's own auto-configuration from {@code spring.data.redis.*} properties -
 * this class only adds the small conveniences built on top of it.
 */
@Configuration
public class RedisConfig {

    @Bean
    public StreamOperations<String, Object, Object> streamOperations(StringRedisTemplate redisTemplate) {
        return redisTemplate.opsForStream();
    }

    @Bean
    @ConfigurationProperties(prefix = "agentplane.stream")
    public StreamProperties streamProperties() {
        return new StreamProperties();
    }
}

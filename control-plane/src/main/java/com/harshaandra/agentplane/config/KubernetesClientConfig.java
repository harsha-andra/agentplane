package com.harshaandra.agentplane.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The fabric8 {@link KubernetesClient} is only constructed when {@code agentplane.k8s.enabled}
 * is {@code true}. By default it is not: AGENTPLANE must start and be fully usable (via
 * {@code orchestration.NoopJobLauncher}) with no Kubernetes cluster present, e.g. running
 * locally through docker-compose. Building a real client eagerly against a cluster that doesn't
 * exist would either fail fast or silently misbehave, so the bean - and the real
 * {@code Fabric8JobLauncher} that depends on it - only exist when explicitly opted in.
 */
@Configuration
@ConditionalOnProperty(prefix = "agentplane.k8s", name = "enabled", havingValue = "true")
public class KubernetesClientConfig {

    @Bean(destroyMethod = "close")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}

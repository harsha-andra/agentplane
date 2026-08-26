package com.harshaandra.agentplane.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports UP with an explanatory detail (rather than DOWN) when no Kubernetes cluster is
 * configured - that is the supported, first-class local/dev mode for this application, not a
 * degraded state. When a real cluster is configured, this indicator actually checks reachability.
 */
@Component
public class KubernetesHealthIndicator implements HealthIndicator {

    private final ObjectProvider<KubernetesClient> kubernetesClientProvider;

    public KubernetesHealthIndicator(ObjectProvider<KubernetesClient> kubernetesClientProvider) {
        this.kubernetesClientProvider = kubernetesClientProvider;
    }

    @Override
    public Health health() {
        KubernetesClient client = kubernetesClientProvider.getIfAvailable();
        if (client == null) {
            return Health.up()
                    .withDetail("mode", "noop")
                    .withDetail("message", "no Kubernetes cluster configured (agentplane.k8s.enabled=false)")
                    .build();
        }
        try {
            String version = client.getKubernetesVersion() != null
                    ? client.getKubernetesVersion().getGitVersion()
                    : "unknown";
            return Health.up().withDetail("mode", "fabric8").withDetail("serverVersion", version).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("mode", "fabric8").build();
        }
    }
}

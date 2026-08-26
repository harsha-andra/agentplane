package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.Tenant;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.LimitRangeItemBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The real implementation of {@link JobLauncher}, backed by the fabric8 Kubernetes client. Only
 * active when {@code agentplane.k8s.enabled=true} - see {@link JobLauncher} for why this is
 * conditional.
 */
@Service
@ConditionalOnProperty(prefix = "agentplane.k8s", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class Fabric8JobLauncher implements JobLauncher {

    private static final String LABEL_RUN_ID = "agentplane.io/run-id";
    private static final String LABEL_TENANT = "agentplane.io/tenant";

    private final KubernetesClient client;

    @Override
    public void provisionTenantNamespace(Tenant tenant) {
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata()
                .withName(tenant.getNamespace())
                .addToLabels("agentplane.io/tenant-slug", tenant.getSlug())
                .endMetadata()
                .build();
        client.resource(namespace).createOrReplace();

        ResourceQuota quota = new ResourceQuotaBuilder()
                .withNewMetadata()
                .withName(tenant.getSlug() + "-quota")
                .withNamespace(tenant.getNamespace())
                .endMetadata()
                .withNewSpec()
                .addToHard("requests.cpu", new Quantity(tenant.getQuotaCpu()))
                .addToHard("requests.memory", new Quantity(tenant.getQuotaMemory()))
                .addToHard("limits.cpu", new Quantity(tenant.getQuotaCpu()))
                .addToHard("limits.memory", new Quantity(tenant.getQuotaMemory()))
                .endSpec()
                .build();
        client.resource(quota).createOrReplace();

        LimitRange limitRange = new LimitRangeBuilder()
                .withNewMetadata()
                .withName(tenant.getSlug() + "-limits")
                .withNamespace(tenant.getNamespace())
                .endMetadata()
                .withNewSpec()
                .addToLimits(new LimitRangeItemBuilder()
                        .withType("Container")
                        .addToDefault("cpu", new Quantity("500m"))
                        .addToDefault("memory", new Quantity("512Mi"))
                        .addToDefaultRequest("cpu", new Quantity("100m"))
                        .addToDefaultRequest("memory", new Quantity("128Mi"))
                        .build())
                .endSpec()
                .build();
        client.resource(limitRange).createOrReplace();

        log.info("Provisioned namespace {} for tenant {}", tenant.getNamespace(), tenant.getSlug());
    }

    @Override
    public LaunchedJob launchJob(AgentRun run, Tenant tenant) {
        String jobName = "agentplane-run-" + run.getId();

        List<EnvVar> envVars = new ArrayList<>(List.of(
                new EnvVarBuilder().withName("AGENTPLANE_RUN_ID").withValue(run.getId().toString()).build(),
                new EnvVarBuilder().withName("AGENTPLANE_PROMPT").withValue(run.getPrompt()).build(),
                new EnvVarBuilder().withName("AGENTPLANE_MODEL").withValue(run.getModel()).build(),
                new EnvVarBuilder().withName("AGENTPLANE_MAX_STEPS").withValue(String.valueOf(run.getMaxSteps())).build()
        ));
        for (Map.Entry<String, String> entry : run.getEnv().entrySet()) {
            envVars.add(new EnvVarBuilder().withName(entry.getKey()).withValue(entry.getValue()).build());
        }

        Job job = new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(tenant.getNamespace())
                .addToLabels(LABEL_RUN_ID, run.getId().toString())
                .addToLabels(LABEL_TENANT, tenant.getSlug())
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds((long) run.getTimeoutSeconds())
                .withTemplate(new PodTemplateSpecBuilder()
                        .withNewMetadata()
                        .addToLabels(LABEL_RUN_ID, run.getId().toString())
                        .endMetadata()
                        .withNewSpec()
                        .withRestartPolicy("Never")
                        .withContainers(new ContainerBuilder()
                                .withName("agent")
                                .withImage(run.getImage())
                                .withEnv(envVars)
                                .withNewResources()
                                .addToRequests("cpu", new Quantity(run.getResourceCpu()))
                                .addToRequests("memory", new Quantity(run.getResourceMemory()))
                                .addToLimits("cpu", new Quantity(run.getResourceCpu()))
                                .addToLimits("memory", new Quantity(run.getResourceMemory()))
                                .endResources()
                                .build())
                        .endSpec()
                        .build())
                .endSpec()
                .build();

        client.batch().v1().jobs().inNamespace(tenant.getNamespace()).resource(job).create();
        log.info("Created Job {} in namespace {} for run {}", jobName, tenant.getNamespace(), run.getId());
        return new LaunchedJob(jobName, tenant.getNamespace());
    }

    @Override
    public void cancelJob(AgentRun run) {
        if (run.getK8sJobName() == null || run.getNamespace() == null) {
            return;
        }
        client.batch().v1().jobs()
                .inNamespace(run.getNamespace())
                .withName(run.getK8sJobName())
                .delete();
        log.info("Deleted Job {} in namespace {} (cancel)", run.getK8sJobName(), run.getNamespace());
    }

    @Override
    public Optional<PodStatusSnapshot> getPodStatus(AgentRun run) {
        if (run.getNamespace() == null) {
            return Optional.empty();
        }
        List<Pod> pods = client.pods()
                .inNamespace(run.getNamespace())
                .withLabel(LABEL_RUN_ID, run.getId().toString())
                .list()
                .getItems();
        if (pods.isEmpty()) {
            return Optional.empty();
        }
        Pod pod = pods.get(0);
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
        Integer exitCode = null;
        int restartCount = 0;
        if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
                && !pod.getStatus().getContainerStatuses().isEmpty()) {
            var cs = pod.getStatus().getContainerStatuses().get(0);
            restartCount = cs.getRestartCount() != null ? cs.getRestartCount() : 0;
            if (cs.getState() != null && cs.getState().getTerminated() != null) {
                exitCode = cs.getState().getTerminated().getExitCode();
            }
        }
        String nodeName = pod.getSpec() != null ? pod.getSpec().getNodeName() : null;
        return Optional.of(new PodStatusSnapshot(phase, exitCode, restartCount, nodeName));
    }
}

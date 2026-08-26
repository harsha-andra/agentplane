package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.Tenant;
import java.util.Optional;

/**
 * Everything the control plane needs from Kubernetes, behind one seam.
 *
 * <p>There are exactly two implementations: {@link Fabric8JobLauncher}, which talks to a real
 * cluster via the fabric8 client, and {@link NoopJobLauncher}, which simulates job execution
 * in-process. Exactly one is active at a time, selected by the {@code agentplane.k8s.enabled}
 * property (see {@code config.KubernetesClientConfig}). This is what lets the whole application
 * - API, persistence, streaming, SSE - start and be fully exercised with no Kubernetes cluster
 * present, e.g. via docker-compose in local development.
 */
public interface JobLauncher {

    /**
     * Provisions (or re-provisions, idempotently) the tenant's namespace with a
     * {@code ResourceQuota} and {@code LimitRange}, so that one customer's workloads cannot
     * starve another's.
     */
    void provisionTenantNamespace(Tenant tenant);

    /** Creates the Kubernetes Job for a run and returns identifying information about it. */
    LaunchedJob launchJob(AgentRun run, Tenant tenant);

    /** Best-effort cancellation of a run's Job/pods. */
    void cancelJob(AgentRun run);

    /** Current observed pod status for a run, if the job/pod can still be found. */
    Optional<PodStatusSnapshot> getPodStatus(AgentRun run);

    record LaunchedJob(String jobName, String namespace) {
    }

    record PodStatusSnapshot(String podPhase, Integer exitCode, int restartCount, String nodeName) {
    }
}

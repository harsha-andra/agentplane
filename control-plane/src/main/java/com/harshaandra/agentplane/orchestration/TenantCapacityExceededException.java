package com.harshaandra.agentplane.orchestration;

import java.util.UUID;

/** Thrown when a tenant already has {@code maxConcurrentRuns} active runs. Mapped to 409. */
public class TenantCapacityExceededException extends RuntimeException {

    public TenantCapacityExceededException(UUID tenantId, int maxConcurrentRuns) {
        super("Tenant " + tenantId + " already has " + maxConcurrentRuns + " concurrent run(s) in flight");
    }
}

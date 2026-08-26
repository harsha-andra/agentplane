package com.harshaandra.agentplane.domain.repository;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/** Dynamic filters backing {@code GET /api/v1/runs?status=&tenantId=&q=}. */
public final class AgentRunSpecifications {

    private AgentRunSpecifications() {
    }

    public static Specification<AgentRun> withFilters(RunStatus status, UUID tenantId, String q) {
        return (root, query, cb) -> {
            var predicates = cb.conjunction();
            if (status != null) {
                predicates = cb.and(predicates, cb.equal(root.get("status"), status));
            }
            if (tenantId != null) {
                predicates = cb.and(predicates, cb.equal(root.get("tenant").get("id"), tenantId));
            }
            if (StringUtils.hasText(q)) {
                String like = "%" + q.toLowerCase() + "%";
                predicates = cb.and(predicates, cb.or(
                        cb.like(cb.lower(root.get("agentName")), like),
                        cb.like(cb.lower(root.get("k8sJobName")), like)));
            }
            return predicates;
        };
    }
}

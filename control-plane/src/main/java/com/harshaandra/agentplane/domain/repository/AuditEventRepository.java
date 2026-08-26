package com.harshaandra.agentplane.domain.repository;

import com.harshaandra.agentplane.domain.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Insert-only access to the audit log. There is intentionally no update/delete method here -
 * see {@link AuditEvent} for why.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByRunIdOrderByCreatedAtAsc(UUID runId);

    List<AuditEvent> findTop20ByOrderByCreatedAtDesc();
}

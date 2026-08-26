package com.harshaandra.agentplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An immutable audit log entry. Every column is {@code updatable = false} and every field is
 * assigned once, in the constructor - there are no setters and no update methods on this class,
 * by design: an audit trail that can be mutated after the fact is not an audit trail. Rows are
 * only ever inserted, never updated or deleted, via {@code AuditEventRepository}.
 */
@Entity
@Table(name = "audit_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditEvent {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;

    @Column(name = "run_id", updatable = false)
    private UUID runId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false, length = 4_000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AuditEvent(UUID tenantId, UUID runId, String eventType, String detail) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.runId = runId;
        this.eventType = eventType;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public static AuditEvent of(UUID tenantId, UUID runId, String eventType, String detail) {
        return new AuditEvent(tenantId, runId, eventType, detail);
    }
}

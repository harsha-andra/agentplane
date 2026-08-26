package com.harshaandra.agentplane.domain.repository;

import com.harshaandra.agentplane.domain.AgentRun;
import com.harshaandra.agentplane.domain.RunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID>, JpaSpecificationExecutor<AgentRun> {

    Optional<AgentRun> findByIdempotencyKey(String idempotencyKey);

    long countByTenantIdAndStatusIn(UUID tenantId, Collection<RunStatus> statuses);

    long countByStatusIn(Collection<RunStatus> statuses);

    List<AgentRun> findTop50ByOrderByCreatedAtDesc();

    List<AgentRun> findByStatusIn(Collection<RunStatus> statuses);

    List<AgentRun> findByCreatedAtAfter(Instant since);

    @Query("select r.status as status, count(r) as total from AgentRun r "
            + "where r.createdAt >= :since group by r.status")
    List<StatusCount> countByStatusSince(@Param("since") Instant since);

    interface StatusCount {
        RunStatus getStatus();

        long getTotal();
    }
}

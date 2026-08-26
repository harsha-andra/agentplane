package com.harshaandra.agentplane.trace;

import java.util.List;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TraceRepository extends MongoRepository<RunTrace, String> {

    List<RunTrace> findByRunIdOrderBySeqAsc(UUID runId);

    long countByRunId(UUID runId);
}

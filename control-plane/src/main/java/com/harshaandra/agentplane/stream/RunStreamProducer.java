package com.harshaandra.agentplane.stream;

import com.harshaandra.agentplane.config.StreamProperties;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

/**
 * Publishes queued-run messages to the Redis Stream at {@code agentplane.stream.stream-key}.
 * Consumed by {@link RunStreamConsumer} via a consumer group, so multiple worker instances can
 * share the queue and any one message is only ever handed to one consumer at a time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunStreamProducer {

    private final StreamOperations<String, Object, Object> streamOperations;
    private final StreamProperties properties;

    public RecordId publish(UUID runId, String idempotencyKey, int attempt) {
        MapRecord<String, Object, Object> record = StreamRecords.newRecord()
                .in(properties.getStreamKey())
                .ofMap(Map.of(
                        "runId", runId.toString(),
                        "idempotencyKey", idempotencyKey,
                        "attempt", String.valueOf(attempt)));
        RecordId id = streamOperations.add(record);
        log.debug("Published run {} to stream {} as {}", runId, properties.getStreamKey(), id);
        return id;
    }
}

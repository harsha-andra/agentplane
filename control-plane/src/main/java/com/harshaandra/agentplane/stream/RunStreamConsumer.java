package com.harshaandra.agentplane.stream;

import com.harshaandra.agentplane.config.StreamProperties;
import com.harshaandra.agentplane.orchestration.RunLaunchProcessor;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer-group listener for the run queue, polling via Spring Data Redis
 * {@link StreamOperations} rather than a raw Lettuce pub/sub subscription so that consumer-group
 * semantics (one message delivered to one consumer, acknowledgement, a per-consumer pending
 * entries list) are available.
 *
 * <p><b>Worker-death handling:</b> a message is only removed from the group's pending entries
 * list (PEL) once {@link #process} completes and calls {@code XACK}. If the worker that read a
 * message dies before acknowledging it, the message stays in the PEL; {@link #reclaimStaleEntries}
 * runs on a fixed schedule, finds entries idle longer than
 * {@code agentplane.stream.visibility-timeout-ms} (the "another worker is now allowed to assume
 * this one is dead" window) and reclaims them via {@code XCLAIM} for this consumer to retry.
 * {@link IdempotencyGuard} then ensures that retry does not re-launch a Kubernetes Job that a
 * half-finished previous attempt already created.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunStreamConsumer {

    private final StreamOperations<String, Object, Object> streamOperations;
    private final StreamProperties properties;
    private final RunLaunchProcessor launchProcessor;

    @Scheduled(fixedDelayString = "${agentplane.stream.poll-timeout-ms:2000}")
    public void pollNewMessages() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!ensureGroup()) {
            return;
        }
        try {
            var records = streamOperations.read(
                    Consumer.from(properties.getConsumerGroup(), properties.getConsumerName()),
                    StreamReadOptions.empty()
                            .count(properties.getPollBatchSize())
                            .block(Duration.ofMillis(properties.getPollTimeoutMs())),
                    StreamOffset.create(properties.getStreamKey(), ReadOffset.lastConsumed()));
            if (records != null) {
                records.forEach(this::process);
            }
        } catch (Exception e) {
            log.debug("Stream poll returned no data / failed (will retry): {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${agentplane.stream.visibility-timeout-ms:60000}")
    public void reclaimStaleEntries() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            PendingMessagesSummary summary =
                    streamOperations.pending(properties.getStreamKey(), properties.getConsumerGroup());
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return;
            }
            PendingMessages pending = streamOperations.pending(
                    properties.getStreamKey(), properties.getConsumerGroup(), Range.unbounded(), properties.getPollBatchSize());
            for (PendingMessage pm : pending) {
                if (pm.getElapsedTimeSinceLastDelivery().toMillis() >= properties.getVisibilityTimeoutMs()) {
                    log.warn("Reclaiming stale stream entry {} (idle {}ms, previously owned by {})",
                            pm.getIdAsString(), pm.getElapsedTimeSinceLastDelivery().toMillis(), pm.getConsumerName());
                    var claimed = streamOperations.claim(
                            properties.getStreamKey(),
                            properties.getConsumerGroup(),
                            properties.getConsumerName(),
                            Duration.ofMillis(properties.getVisibilityTimeoutMs()),
                            pm.getId());
                    claimed.forEach(this::process);
                }
            }
        } catch (Exception e) {
            log.warn("Pending-entry reclaim failed (will retry next cycle): {}", e.getMessage());
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        try {
            UUID runId = UUID.fromString(String.valueOf(value.get("runId")));
            String idempotencyKey = String.valueOf(value.get("idempotencyKey"));
            int attempt = Integer.parseInt(String.valueOf(value.get("attempt")));
            launchProcessor.processQueuedRun(runId, idempotencyKey, attempt);
            streamOperations.acknowledge(properties.getStreamKey(), properties.getConsumerGroup(), record.getId());
        } catch (Exception e) {
            log.error("Failed to process stream record {} - leaving unacknowledged for retry/reclaim: {}",
                    record.getId(), e.getMessage(), e);
        }
    }

    /** Idempotent: creates the consumer group if missing, tolerating a not-yet-existing stream. */
    private boolean ensureGroup() {
        try {
            streamOperations.createGroup(properties.getStreamKey(), ReadOffset.from("0"), properties.getConsumerGroup());
            return true;
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("BUSYGROUP")) {
                return true; // already exists - fine
            }
            log.debug("Consumer group not ready yet (stream may not exist until first publish): {}", message);
            return false;
        }
    }
}

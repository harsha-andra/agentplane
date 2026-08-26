package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.RunStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for which {@link RunStatus} transitions are legal.
 *
 * <pre>
 * PENDING   -> SCHEDULED, FAILED, CANCELLED
 * SCHEDULED -> RUNNING, FAILED, CANCELLED, TIMED_OUT
 * RUNNING   -> SUCCEEDED, FAILED, CANCELLED, TIMED_OUT
 * (terminal: SUCCEEDED, FAILED, CANCELLED, TIMED_OUT) -> nothing
 * </pre>
 */
@Component
public class RunStateMachine {

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED = new EnumMap<>(RunStatus.class);

    static {
        ALLOWED.put(RunStatus.PENDING, EnumSet.of(RunStatus.SCHEDULED, RunStatus.FAILED, RunStatus.CANCELLED));
        ALLOWED.put(RunStatus.SCHEDULED, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT));
        ALLOWED.put(RunStatus.RUNNING, EnumSet.of(RunStatus.SUCCEEDED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT));
        ALLOWED.put(RunStatus.SUCCEEDED, EnumSet.noneOf(RunStatus.class));
        ALLOWED.put(RunStatus.FAILED, EnumSet.noneOf(RunStatus.class));
        ALLOWED.put(RunStatus.CANCELLED, EnumSet.noneOf(RunStatus.class));
        ALLOWED.put(RunStatus.TIMED_OUT, EnumSet.noneOf(RunStatus.class));
    }

    public boolean canTransition(RunStatus from, RunStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void validateTransition(RunStatus from, RunStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidRunTransitionException(from, to);
        }
    }
}

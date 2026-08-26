package com.harshaandra.agentplane.domain;

/**
 * Lifecycle states of an {@link AgentRun}.
 *
 * <p>Transitions are enforced by {@code orchestration.RunStateMachine}, not by this enum -
 * this type is intentionally a plain data label so it can be shared by JPA, the REST API layer
 * and MapStruct without pulling in orchestration concerns.
 */
public enum RunStatus {
    PENDING,
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    /** Terminal states never transition further. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}

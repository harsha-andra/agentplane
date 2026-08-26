package com.harshaandra.agentplane.orchestration;

import com.harshaandra.agentplane.domain.RunStatus;

/** Thrown when code attempts a run status transition that {@link RunStateMachine} disallows. */
public class InvalidRunTransitionException extends RuntimeException {

    public InvalidRunTransitionException(RunStatus from, RunStatus to) {
        super("Cannot transition run from " + from + " to " + to);
    }
}

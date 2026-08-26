package com.harshaandra.agentplane.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harshaandra.agentplane.domain.RunStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class RunStateMachineTest {

    private final RunStateMachine stateMachine = new RunStateMachine();

    @Test
    void allowsTheHappyPath() {
        assertThat(stateMachine.canTransition(RunStatus.PENDING, RunStatus.SCHEDULED)).isTrue();
        assertThat(stateMachine.canTransition(RunStatus.SCHEDULED, RunStatus.RUNNING)).isTrue();
        assertThat(stateMachine.canTransition(RunStatus.RUNNING, RunStatus.SUCCEEDED)).isTrue();
    }

    @Test
    void allowsCancellationFromAnyNonTerminalState() {
        assertThat(stateMachine.canTransition(RunStatus.PENDING, RunStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.canTransition(RunStatus.SCHEDULED, RunStatus.CANCELLED)).isTrue();
        assertThat(stateMachine.canTransition(RunStatus.RUNNING, RunStatus.CANCELLED)).isTrue();
    }

    @Test
    void rejectsSkippingScheduled() {
        assertThat(stateMachine.canTransition(RunStatus.PENDING, RunStatus.RUNNING)).isFalse();
    }

    @Test
    void rejectsGoingBackwards() {
        assertThat(stateMachine.canTransition(RunStatus.RUNNING, RunStatus.PENDING)).isFalse();
        assertThat(stateMachine.canTransition(RunStatus.SCHEDULED, RunStatus.PENDING)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT"})
    void terminalStatesAcceptNoFurtherTransitions(RunStatus terminal) {
        for (RunStatus target : RunStatus.values()) {
            assertThat(stateMachine.canTransition(terminal, target)).isFalse();
        }
    }

    @Test
    void validateTransitionThrowsOnInvalidMove() {
        assertThatThrownBy(() -> stateMachine.validateTransition(RunStatus.SUCCEEDED, RunStatus.RUNNING))
                .isInstanceOf(InvalidRunTransitionException.class)
                .hasMessageContaining("SUCCEEDED")
                .hasMessageContaining("RUNNING");
    }

    @Test
    void validateTransitionIsSilentOnValidMove() {
        stateMachine.validateTransition(RunStatus.PENDING, RunStatus.SCHEDULED);
    }
}

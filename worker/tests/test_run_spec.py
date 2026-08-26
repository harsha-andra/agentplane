import pytest

from agentplane_worker.run_spec import RunSpec, RunSpecError


def test_reads_from_environment(monkeypatch):
    monkeypatch.setenv("AGENTPLANE_RUN_ID", "run-123")
    monkeypatch.setenv("AGENTPLANE_PROMPT", "do the thing")
    monkeypatch.setenv("AGENTPLANE_MODEL", "gpt-test")
    monkeypatch.setenv("AGENTPLANE_MAX_STEPS", "5")
    monkeypatch.delenv("IDEMPOTENCY_KEY", raising=False)

    spec = RunSpec.from_env_and_args([])

    assert spec.run_id == "run-123"
    assert spec.prompt == "do the thing"
    assert spec.model == "gpt-test"
    assert spec.max_steps == 5
    # documented fallback: no IDEMPOTENCY_KEY env var -> falls back to the run id
    assert spec.idempotency_key == "run-123"


def test_idempotency_key_env_var_takes_precedence_over_fallback(monkeypatch):
    monkeypatch.setenv("AGENTPLANE_RUN_ID", "run-123")
    monkeypatch.setenv("AGENTPLANE_PROMPT", "p")
    monkeypatch.setenv("AGENTPLANE_MODEL", "m")
    monkeypatch.setenv("AGENTPLANE_MAX_STEPS", "1")
    monkeypatch.setenv("IDEMPOTENCY_KEY", "explicit-key")

    spec = RunSpec.from_env_and_args([])

    assert spec.idempotency_key == "explicit-key"


def test_cli_args_override_environment(monkeypatch):
    monkeypatch.setenv("AGENTPLANE_RUN_ID", "run-from-env")
    monkeypatch.setenv("AGENTPLANE_PROMPT", "env prompt")
    monkeypatch.setenv("AGENTPLANE_MODEL", "env-model")
    monkeypatch.setenv("AGENTPLANE_MAX_STEPS", "1")

    spec = RunSpec.from_env_and_args(["--run-id", "run-from-cli", "--prompt", "cli prompt"])

    assert spec.run_id == "run-from-cli"
    assert spec.prompt == "cli prompt"
    assert spec.model == "env-model"  # not overridden, still comes from env


def test_missing_required_field_raises(monkeypatch):
    monkeypatch.delenv("AGENTPLANE_RUN_ID", raising=False)
    monkeypatch.delenv("AGENTPLANE_PROMPT", raising=False)
    monkeypatch.delenv("AGENTPLANE_MODEL", raising=False)
    monkeypatch.delenv("AGENTPLANE_MAX_STEPS", raising=False)

    with pytest.raises(RunSpecError, match="run-id"):
        RunSpec.from_env_and_args([])


def test_non_integer_max_steps_raises(monkeypatch):
    monkeypatch.setenv("AGENTPLANE_RUN_ID", "run-1")
    monkeypatch.setenv("AGENTPLANE_PROMPT", "p")
    monkeypatch.setenv("AGENTPLANE_MODEL", "m")
    monkeypatch.setenv("AGENTPLANE_MAX_STEPS", "not-a-number")

    with pytest.raises(RunSpecError, match="integer"):
        RunSpec.from_env_and_args([])


def test_zero_max_steps_raises(monkeypatch):
    monkeypatch.setenv("AGENTPLANE_RUN_ID", "run-1")
    monkeypatch.setenv("AGENTPLANE_PROMPT", "p")
    monkeypatch.setenv("AGENTPLANE_MODEL", "m")
    monkeypatch.setenv("AGENTPLANE_MAX_STEPS", "0")

    with pytest.raises(RunSpecError, match=">= 1"):
        RunSpec.from_env_and_args([])

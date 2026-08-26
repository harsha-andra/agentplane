import json

from agentplane_worker.idempotency import Checkpoint, IdempotencyStore


def test_unknown_key_has_no_checkpoint(tmp_path):
    store = IdempotencyStore(tmp_path)
    assert store.load("does-not-exist") is None
    assert store.is_finished("does-not-exist") is False
    assert store.is_step_complete("does-not-exist", 0) is False


def test_record_step_then_reload_sees_it(tmp_path):
    store = IdempotencyStore(tmp_path)
    store.record_step("run-1", 0, {"hits": ["a"]})

    reloaded = IdempotencyStore(tmp_path)  # simulates a fresh process reading the same dir
    assert reloaded.is_step_complete("run-1", 0) is True
    assert reloaded.is_step_complete("run-1", 1) is False
    checkpoint = reloaded.load("run-1")
    assert checkpoint.completed_steps[0] == {"hits": ["a"]}


def test_record_finished_marks_terminal_status(tmp_path):
    store = IdempotencyStore(tmp_path)
    store.record_step("run-1", 0, {"ok": True})
    store.record_finished("run-1", "SUCCEEDED", {"toolResults": {"search_docs": {"ok": True}}})

    assert store.is_finished("run-1") is True
    checkpoint = store.load("run-1")
    assert checkpoint.status == "SUCCEEDED"
    assert checkpoint.result["toolResults"]["search_docs"]["ok"] is True
    # the step recorded before finishing is still there
    assert checkpoint.completed_steps[0] == {"ok": True}


def test_write_is_atomic_no_temp_files_left_behind(tmp_path):
    store = IdempotencyStore(tmp_path)
    store.record_step("run-1", 0, {"a": 1})
    store.record_step("run-1", 1, {"b": 2})

    files = list(tmp_path.iterdir())
    assert [f.name for f in files] == ["run-1.json"]  # no leftover .tmp-*.json


def test_corrupt_checkpoint_file_is_treated_as_absent(tmp_path):
    (tmp_path / "run-1.json").write_text("{not valid json")
    store = IdempotencyStore(tmp_path)

    assert store.load("run-1") is None
    assert store.is_finished("run-1") is False


def test_idempotency_key_is_sanitised_for_the_filesystem(tmp_path):
    store = IdempotencyStore(tmp_path)
    weird_key = "../../etc/passwd"
    store.record_step(weird_key, 0, {"x": 1})

    # must not have escaped the checkpoint directory
    assert not (tmp_path.parent / "etc").exists()
    files = list(tmp_path.iterdir())
    assert len(files) == 1
    assert files[0].parent == tmp_path


def test_checkpoint_json_round_trip():
    checkpoint = Checkpoint(
        idempotency_key="run-1",
        completed_steps={0: {"a": 1}, 2: {"c": 3}},
        status="FAILED",
        result={"error": "boom"},
    )
    restored = Checkpoint.from_json(json.loads(json.dumps(checkpoint.to_json())))

    assert restored.completed_steps == {0: {"a": 1}, 2: {"c": 3}}
    assert restored.status == "FAILED"
    assert restored.result == {"error": "boom"}

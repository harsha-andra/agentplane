import io
import json

from agentplane_worker.trace import ToolTimer, TraceEmitter


def _emitter() -> tuple[TraceEmitter, io.StringIO]:
    emitter = TraceEmitter(run_id="run-1")
    stream = io.StringIO()
    emitter._stream = stream  # redirect from stdout for assertions
    return emitter, stream


def _events(stream: io.StringIO) -> list[dict]:
    return [json.loads(line) for line in stream.getvalue().splitlines()]


def test_log_event_is_one_json_line_with_required_fields():
    emitter, stream = _emitter()
    emitter.log("INFO", "hello", foo="bar")

    events = _events(stream)
    assert len(events) == 1
    event = events[0]
    assert event["type"] == "LOG"
    assert event["runId"] == "run-1"
    assert event["seq"] == 1
    assert event["message"] == "hello"
    assert event["payload"] == {"foo": "bar"}
    assert "startedAt" in event


def test_seq_increments_across_calls():
    emitter, stream = _emitter()
    emitter.log("INFO", "one")
    emitter.step(0, "STARTED")
    emitter.tool_call("search_docs", "SUCCESS", latency_ms=12)

    events = _events(stream)
    assert [e["seq"] for e in events] == [1, 2, 3]
    assert events[1]["type"] == "STEP"
    assert events[2]["type"] == "TOOL_CALL"


def test_tool_timer_emits_success_event_with_latency():
    emitter, stream = _emitter()

    with ToolTimer(emitter, "fetch_metric"):
        pass

    event = _events(stream)[0]
    assert event["type"] == "TOOL_CALL"
    assert event["toolName"] == "fetch_metric"
    assert event["status"] == "SUCCESS"
    assert event["error"] is None
    assert isinstance(event["latencyMs"], int)
    assert event["latencyMs"] >= 0


def test_tool_timer_emits_error_event_and_reraises():
    emitter, stream = _emitter()

    try:
        with ToolTimer(emitter, "fetch_metric"):
            raise RuntimeError("boom")
    except RuntimeError:
        pass
    else:
        raise AssertionError("ToolTimer must not swallow the exception")

    event = _events(stream)[0]
    assert event["status"] == "ERROR"
    assert event["error"] == "boom"

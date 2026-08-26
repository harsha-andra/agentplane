import pytest

from agentplane_worker.tools import ToolError, fetch_metric, search_docs, summarize


def test_search_docs_is_deterministic_for_the_same_query():
    first = search_docs("tool latency regression")
    second = search_docs("tool latency regression")
    assert first == second
    assert len(first["hits"]) == 2


def test_search_docs_differs_for_different_queries():
    assert search_docs("a") != search_docs("b")


def test_fetch_metric_rejects_empty_name():
    with pytest.raises(ToolError):
        fetch_metric("")


def test_summarize_rejects_empty_fragments():
    with pytest.raises(ToolError):
        summarize([])


def test_summarize_joins_and_truncates():
    result = summarize(["a", "b", "c"])
    assert result["summary"] == "a b c"
    assert result["fragmentCount"] == 3

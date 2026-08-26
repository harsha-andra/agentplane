from agentplane_worker.graph import END, GraphError, StateGraph


def test_linear_graph_runs_in_order():
    trace: list[str] = []

    def a(state):
        trace.append("a")
        state["a"] = True
        return state

    def b(state):
        trace.append("b")
        state["b"] = True
        return state

    graph = StateGraph().add_node("a", a).add_node("b", b).set_entry_point("a")
    graph.add_edge("a", "b").add_edge("b", END)
    compiled = graph.compile()

    result = compiled.invoke({})

    assert trace == ["a", "b"]
    assert result == {"a": True, "b": True}


def test_conditional_edge_loops_until_router_ends_it():
    def increment(state):
        state["count"] += 1
        return state

    def router(state):
        return "increment" if state["count"] < 3 else END

    graph = StateGraph().add_node("increment", increment).set_entry_point("increment")
    graph.add_conditional_edges("increment", router)
    compiled = graph.compile()

    result = compiled.invoke({"count": 0})

    assert result["count"] == 3


def test_node_with_no_outgoing_edge_implicitly_ends():
    graph = StateGraph().add_node("only", lambda s: s).set_entry_point("only")
    compiled = graph.compile()

    assert compiled.invoke({"x": 1}) == {"x": 1}


def test_compile_requires_entry_point():
    try:
        StateGraph().add_node("a", lambda s: s).compile()
    except GraphError as exc:
        assert "entry point" in str(exc)
    else:
        raise AssertionError("expected GraphError")


def test_compile_rejects_edge_to_unknown_node():
    graph = StateGraph().add_node("a", lambda s: s).set_entry_point("a")
    graph.add_edge("a", "does-not-exist")
    try:
        graph.compile()
    except GraphError as exc:
        assert "does-not-exist" in str(exc)
    else:
        raise AssertionError("expected GraphError")


def test_runaway_graph_hits_max_steps_safety_valve():
    graph = StateGraph().add_node("loop", lambda s: s).set_entry_point("loop")
    graph.add_edge("loop", "loop")  # never reaches END
    compiled = graph.compile(max_steps=5)

    try:
        compiled.invoke({})
    except GraphError as exc:
        assert "max_steps=5" in str(exc)
    else:
        raise AssertionError("expected GraphError")

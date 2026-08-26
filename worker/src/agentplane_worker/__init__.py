"""AGENTPLANE worker: the Kubernetes Job payload launched by Fabric8JobLauncher.

See ``README.md`` in this directory for the full picture. In one line: read a run
spec from the environment, walk a small LangGraph-style state machine that makes
a few tool calls, emit structured JSON trace events on stdout, and report the
final status back to the control plane over an mTLS connection.
"""

__version__ = "0.1.0"

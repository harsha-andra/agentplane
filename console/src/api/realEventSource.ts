// Real-backend transport: GET /api/v1/runs/{id}/events as Server-Sent
// Events. We intentionally let our own hook own reconnect/backoff instead
// of relying on the browser's built-in EventSource retry, so the UI gets an
// explicit, observable connection-status state machine.

import { API_BASE } from '../config'
import type { OpenEventStream } from './eventStreamTypes'

export const openRealEventStream: OpenEventStream = (runId, handlers) => {
  const url = `${API_BASE}/runs/${encodeURIComponent(runId)}/events`
  const source = new EventSource(url)

  source.onopen = () => handlers.onOpen()
  source.onmessage = (ev: MessageEvent<string>) => {
    try {
      handlers.onEvent(JSON.parse(ev.data))
    } catch {
      // Ignore malformed frames rather than tearing down the connection.
    }
  }
  source.onerror = () => {
    source.close()
    handlers.onError()
  }

  return {
    close: () => source.close(),
  }
}

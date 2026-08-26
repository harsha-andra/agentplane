// Shared transport abstraction for the run event stream. Both the demo
// (simulated) source and the real browser EventSource implement this same
// shape, so the reconnect/backoff hook (hooks/useRunEventStream.ts) doesn't
// need to know or care which one it's talking to.

import type { RunEvent } from '../types/api'

export interface EventStreamHandlers {
  onOpen: () => void
  onEvent: (event: RunEvent) => void
  onError: () => void
}

export interface EventStreamHandle {
  close: () => void
}

export type OpenEventStream = (runId: string, handlers: EventStreamHandlers) => EventStreamHandle

// Demo-mode transport for the live log tail. Polls the seeded store for
// events whose timestamp has now "arrived" (see store.ts materialize/
// getVisibleEvents) and delivers only the ones the caller hasn't seen yet.
// Occasionally simulates a transient connection blip so the reconnect/
// backoff path in useRunEventStream actually gets exercised during a demo.

import { getVisibleEvents } from './store'
import type { OpenEventStream } from '../api/eventStreamTypes'

const POLL_MS = 300
/** Probability of a simulated transient disconnect on any given poll tick
 * while the stream is open — tuned so a viewer watching a live run for a
 * minute or two will likely see the reconnect indicator at least once. */
const TRANSIENT_ERROR_PROBABILITY = 0.015

export const openFakeEventStream: OpenEventStream = (runId, handlers) => {
  let closed = false
  let deliveredSeq = 0
  let firstPoll = true
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  const staggerTimers: ReturnType<typeof setTimeout>[] = []

  const connectDelay = 180 + Math.random() * 260
  const connectTimer = setTimeout(() => {
    if (closed) return
    handlers.onOpen()
    tick()
  }, connectDelay)

  function tick() {
    if (closed) return

    let visible
    try {
      visible = getVisibleEvents(runId, Date.now())
    } catch {
      handlers.onError()
      return
    }

    const fresh = visible.filter((e) => e.seq > deliveredSeq)
    if (fresh.length > 0) {
      // Replay a backlog with a brief, visible stagger rather than dumping
      // it in a single frame — reads more like a real tail.
      const stagger = firstPoll ? Math.min(35, Math.max(6, Math.floor(500 / fresh.length))) : 0
      fresh.forEach((event, i) => {
        const t = setTimeout(() => {
          if (!closed) handlers.onEvent(event)
        }, stagger * i)
        staggerTimers.push(t)
      })
      deliveredSeq = fresh[fresh.length - 1].seq
    }
    firstPoll = false

    if (Math.random() < TRANSIENT_ERROR_PROBABILITY) {
      handlers.onError()
      return
    }

    pollTimer = setTimeout(tick, POLL_MS)
  }

  return {
    close: () => {
      closed = true
      clearTimeout(connectTimer)
      if (pollTimer) clearTimeout(pollTimer)
      staggerTimers.forEach(clearTimeout)
    },
  }
}

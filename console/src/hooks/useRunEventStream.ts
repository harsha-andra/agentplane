// Drives the live log tail on the run detail page. Owns the connection to
// GET /runs/{id}/events (SSE in real mode, a simulated source in demo mode
// — see api/eventStream.ts) and exposes an explicit connection-status state
// machine plus exponential backoff reconnect, so the UI can show
// connecting/live/reconnecting/closed rather than just "did it work".

import { useCallback, useEffect, useRef, useState } from 'react'
import { openRunEventStream } from '../api/eventStream'
import type { EventStreamHandle } from '../api/eventStreamTypes'
import type { RunEvent } from '../types/api'

export type ConnectionStatus = 'connecting' | 'live' | 'reconnecting' | 'closed'
export type ClosedReason = 'completed' | 'max-attempts' | null

const TERMINAL_PHASES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'])

export interface UseRunEventStreamOptions {
  enabled?: boolean
  maxAttempts?: number
  baseDelayMs?: number
  maxDelayMs?: number
}

export interface UseRunEventStreamResult {
  events: RunEvent[]
  status: ConnectionStatus
  attempt: number
  closedReason: ClosedReason
  isPaused: boolean
  pause: () => void
  resume: () => void
  reconnect: () => void
  clear: () => void
}

export function useRunEventStream(runId: string | undefined, options: UseRunEventStreamOptions = {}): UseRunEventStreamResult {
  const { enabled = true, maxAttempts = 6, baseDelayMs = 500, maxDelayMs = 15_000 } = options

  const [events, setEvents] = useState<RunEvent[]>([])
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [attempt, setAttempt] = useState(0)
  const [closedReason, setClosedReason] = useState<ClosedReason>(null)
  const [isPaused, setIsPaused] = useState(false)

  const handleRef = useRef<EventStreamHandle | null>(null)
  const backoffTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const attemptRef = useRef(0)
  const seenSeqRef = useRef<Set<number>>(new Set())
  // Bumped on every (re)connect and unmount so callbacks from a superseded
  // attempt (or a previous runId) can't clobber state after the fact.
  const generationRef = useRef(0)

  const clearBackoff = () => {
    if (backoffTimerRef.current) {
      clearTimeout(backoffTimerRef.current)
      backoffTimerRef.current = null
    }
  }

  const teardown = useCallback(() => {
    clearBackoff()
    if (handleRef.current) {
      handleRef.current.close()
      handleRef.current = null
    }
  }, [])

  const connect = useCallback(
    (generation: number) => {
      if (!runId) return
      teardown()
      setStatus('connecting')
      handleRef.current = openRunEventStream(runId, {
        onOpen: () => {
          if (generation !== generationRef.current) return
          attemptRef.current = 0
          setAttempt(0)
          setStatus('live')
        },
        onEvent: (event) => {
          if (generation !== generationRef.current) return
          if (seenSeqRef.current.has(event.seq)) return
          seenSeqRef.current.add(event.seq)
          setEvents((prev) => [...prev, event])
          if (event.phase && TERMINAL_PHASES.has(event.phase)) {
            teardown()
            setStatus('closed')
            setClosedReason('completed')
          }
        },
        onError: () => {
          if (generation !== generationRef.current) return
          teardown()
          const nextAttempt = attemptRef.current + 1
          attemptRef.current = nextAttempt
          setAttempt(nextAttempt)
          if (nextAttempt > maxAttempts) {
            setStatus('closed')
            setClosedReason('max-attempts')
            return
          }
          setStatus('reconnecting')
          const backoff = Math.min(maxDelayMs, baseDelayMs * 2 ** (nextAttempt - 1))
          const jittered = backoff * (0.75 + Math.random() * 0.5)
          backoffTimerRef.current = setTimeout(() => connect(generation), jittered)
        },
      })
    },
    [runId, maxAttempts, baseDelayMs, maxDelayMs, teardown],
  )

  useEffect(() => {
    if (!runId || !enabled) {
      setStatus('closed')
      setClosedReason(null)
      return
    }
    generationRef.current += 1
    const generation = generationRef.current
    seenSeqRef.current = new Set()
    setEvents([])
    setAttempt(0)
    attemptRef.current = 0
    setClosedReason(null)
    connect(generation)
    return () => {
      generationRef.current += 1
      teardown()
    }
    // `connect` intentionally excluded: it's recreated when its deps change,
    // but reconnecting on every render-scoped identity change would defeat
    // the point of a stable stream keyed on runId/enabled.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId, enabled])

  const pause = useCallback(() => setIsPaused(true), [])
  const resume = useCallback(() => setIsPaused(false), [])
  const clear = useCallback(() => {
    seenSeqRef.current = new Set()
    setEvents([])
  }, [])
  const reconnect = useCallback(() => {
    generationRef.current += 1
    const generation = generationRef.current
    attemptRef.current = 0
    setAttempt(0)
    setClosedReason(null)
    connect(generation)
  }, [connect])

  return { events, status, attempt, closedReason, isPaused, pause, resume, reconnect, clear }
}

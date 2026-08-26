import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EventStreamHandlers } from '../api/eventStreamTypes'
import { useRunEventStream } from './useRunEventStream'

// vi.mock is hoisted above these imports by Vitest's transform, so the
// mock factory can't close over a plain top-level `const` — vi.hoisted
// gives it something already initialized by the time it runs.
const { openMock } = vi.hoisted(() => ({ openMock: vi.fn() }))

vi.mock('../api/eventStream', () => ({
  openRunEventStream: (runId: string, handlers: EventStreamHandlers) => openMock(runId, handlers),
}))

function makeEvent(seq: number, overrides: Partial<Parameters<EventStreamHandlers['onEvent']>[0]> = {}) {
  return {
    seq,
    runId: 'run_1',
    ts: new Date().toISOString(),
    level: 'INFO' as const,
    source: 'agent-runtime',
    message: `line ${seq}`,
    ...overrides,
  }
}

describe('useRunEventStream', () => {
  let handlersList: EventStreamHandlers[]
  let closeMocks: ReturnType<typeof vi.fn>[]

  beforeEach(() => {
    vi.useFakeTimers()
    handlersList = []
    closeMocks = []
    openMock.mockReset()
    openMock.mockImplementation((_runId: string, handlers: EventStreamHandlers) => {
      handlersList.push(handlers)
      const close = vi.fn()
      closeMocks.push(close)
      return { close }
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts connecting and transitions to live once the transport opens', () => {
    const { result } = renderHook(() => useRunEventStream('run_1'))
    expect(result.current.status).toBe('connecting')

    act(() => handlersList[0].onOpen())
    expect(result.current.status).toBe('live')
  })

  it('appends events in arrival order and dedupes repeated seq numbers', () => {
    const { result } = renderHook(() => useRunEventStream('run_1'))

    act(() => {
      handlersList[0].onOpen()
      handlersList[0].onEvent(makeEvent(1))
      handlersList[0].onEvent(makeEvent(1, { message: 'duplicate' }))
      handlersList[0].onEvent(makeEvent(2))
    })

    expect(result.current.events).toHaveLength(2)
    expect(result.current.events[0].message).toBe('line 1')
    expect(result.current.events[1].message).toBe('line 2')
  })

  it('reconnects with exponential backoff after an error, and stops after maxAttempts', () => {
    const { result } = renderHook(() => useRunEventStream('run_1', { maxAttempts: 2, baseDelayMs: 100, maxDelayMs: 1000 }))

    act(() => handlersList[0].onOpen())
    expect(openMock).toHaveBeenCalledTimes(1)

    act(() => handlersList[0].onError())
    expect(result.current.status).toBe('reconnecting')
    expect(result.current.attempt).toBe(1)
    expect(closeMocks[0]).toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(openMock).toHaveBeenCalledTimes(2)

    act(() => handlersList[1].onError())
    expect(result.current.status).toBe('reconnecting')
    expect(result.current.attempt).toBe(2)

    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(openMock).toHaveBeenCalledTimes(3)

    // A third failure exceeds maxAttempts (2) — give up rather than retry
    // forever, and surface why.
    act(() => handlersList[2].onError())
    expect(result.current.status).toBe('closed')
    expect(result.current.closedReason).toBe('max-attempts')
    expect(openMock).toHaveBeenCalledTimes(3)
  })

  it('resets the attempt counter after a successful reconnect', () => {
    const { result } = renderHook(() => useRunEventStream('run_1', { maxAttempts: 5, baseDelayMs: 50 }))

    act(() => handlersList[0].onOpen())
    act(() => handlersList[0].onError())
    expect(result.current.attempt).toBe(1)

    act(() => {
      vi.advanceTimersByTime(200)
    })
    act(() => handlersList[1].onOpen())

    expect(result.current.status).toBe('live')
    expect(result.current.attempt).toBe(0)
  })

  it('closes without scheduling a reconnect once a terminal-phase event arrives', () => {
    const { result } = renderHook(() => useRunEventStream('run_1'))

    act(() => {
      handlersList[0].onOpen()
      handlersList[0].onEvent(makeEvent(1, { phase: 'COMPLETED', message: 'Run completed successfully' }))
    })

    expect(result.current.status).toBe('closed')
    expect(result.current.closedReason).toBe('completed')
    expect(closeMocks[0]).toHaveBeenCalled()

    // No further connection attempts should be scheduled.
    act(() => {
      vi.advanceTimersByTime(30_000)
    })
    expect(openMock).toHaveBeenCalledTimes(1)
  })

  it('pause/resume toggle isPaused without touching the connection', () => {
    const { result } = renderHook(() => useRunEventStream('run_1'))

    act(() => result.current.pause())
    expect(result.current.isPaused).toBe(true)

    act(() => result.current.resume())
    expect(result.current.isPaused).toBe(false)
    expect(openMock).toHaveBeenCalledTimes(1)
  })
})

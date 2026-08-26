import { useEffect, useRef } from 'react'
import { ChevronsDown, Pause, Play, RotateCcw } from 'lucide-react'
import { useRunEventStream, type ConnectionStatus } from '../hooks/useRunEventStream'
import { formatClockTime } from '../lib/format'
import { severityColorVar } from '../lib/severity'
import { EmptyState } from './EmptyState'
import styles from './LiveLogTail.module.css'

const STATUS_LABEL: Record<ConnectionStatus, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  reconnecting: 'Reconnecting…',
  closed: 'Closed',
}

const STATUS_COLOR: Record<ConnectionStatus, string> = {
  connecting: 'var(--status-scheduled)',
  live: 'var(--status-succeeded)',
  reconnecting: 'var(--status-running)',
  closed: 'var(--text-disabled)',
}

export interface LiveLogTailProps {
  runId: string | undefined
  enabled?: boolean
}

/** The centerpiece: a live-tailing terminal pane fed by SSE (GET
 * /runs/{id}/events in real mode; a simulated, time-scripted source in demo
 * mode — see hooks/useRunEventStream.ts). Status is pushed to the browser,
 * not polled for. */
export function LiveLogTail({ runId, enabled = true }: LiveLogTailProps) {
  const stream = useRunEventStream(runId, { enabled })
  const termRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (stream.isPaused) return
    const el = termRef.current
    if (!el) return
    el.scrollTop = el.scrollHeight
  }, [stream.events.length, stream.isPaused])

  return (
    <div className={styles.wrap}>
      <div className={styles.toolbar}>
        <span className={styles.statusGroup} style={{ color: STATUS_COLOR[stream.status] }}>
          <span className={`${styles.statusDot} ${stream.status === 'live' || stream.status === 'reconnecting' ? styles.pulse : ''}`} />
          {STATUS_LABEL[stream.status]}
          {stream.status === 'reconnecting' && stream.attempt > 0 ? ` (attempt ${stream.attempt})` : ''}
        </span>
        <span className="mono text-tertiary" style={{ fontSize: 'var(--text-2xs)' }}>
          {stream.events.length} line{stream.events.length === 1 ? '' : 's'}
        </span>
        <div className={styles.spacer} />
        {stream.status === 'closed' && stream.closedReason === 'max-attempts' && (
          <button type="button" className="btn btn-sm" onClick={stream.reconnect}>
            <RotateCcw size={13} />
            Reconnect
          </button>
        )}
        <button
          type="button"
          className="btn btn-sm"
          onClick={stream.isPaused ? stream.resume : stream.pause}
          aria-pressed={stream.isPaused}
        >
          {stream.isPaused ? <Play size={13} /> : <Pause size={13} />}
          {stream.isPaused ? 'Resume' : 'Pause'}
        </button>
      </div>

      <div className={styles.term} ref={termRef}>
        {stream.events.length === 0 ? (
          <EmptyState title={stream.status === 'connecting' ? 'Connecting to log stream…' : 'No log lines yet'} />
        ) : (
          stream.events.map((e) => (
            <div key={e.seq} className={styles.line}>
              <span className={`mono ${styles.time}`}>{formatClockTime(e.ts)}</span>
              <span className={`mono ${styles.level}`} style={{ color: severityColorVar(e.level) }}>
                {e.level}
              </span>
              <span className={`mono ${styles.source}`}>{e.source}</span>
              <span className="mono" style={{ color: e.level === 'ERROR' ? severityColorVar('ERROR') : 'var(--text-primary)' }}>
                {e.message}
              </span>
            </div>
          ))
        )}
      </div>

      {stream.isPaused && (
        <div className={styles.pausedBanner}>
          <ChevronsDown size={12} />
          Paused — new lines are buffering. Resume to jump to the latest.
        </div>
      )}
    </div>
  )
}

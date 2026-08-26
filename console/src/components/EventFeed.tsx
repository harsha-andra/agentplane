import { Link } from 'react-router-dom'
import type { RunEvent } from '../types/api'
import { formatClockTime, truncateId } from '../lib/format'
import { severityColorVar } from '../lib/severity'
import { EmptyState } from './EmptyState'
import { Radio } from 'lucide-react'
import styles from './EventFeed.module.css'

export interface EventFeedProps {
  events: RunEvent[]
}

/** Cross-run "live cluster events" feed for the Overview page — every row
 * links back to the run it came from. */
export function EventFeed({ events }: EventFeedProps) {
  if (events.length === 0) {
    return <EmptyState icon={<Radio size={24} strokeWidth={1.5} />} title="No recent events" body="Cluster activity will appear here as runs progress." />
  }

  return (
    <ul className={styles.list}>
      {events.map((e) => (
        <li key={`${e.runId}-${e.seq}`} className={styles.row}>
          <span className={`mono ${styles.time}`}>{formatClockTime(e.ts)}</span>
          <span className={styles.dot} style={{ color: severityColorVar(e.level) }} aria-hidden="true" />
          <span className={`mono ${styles.source}`}>{e.source}</span>
          <Link to={`/runs/${e.runId}`} className={`mono ${styles.runLink}`} title={e.runId}>
            {truncateId(e.runId, 8, 0)}
          </Link>
          <span className={styles.message}>{e.message}</span>
        </li>
      ))}
    </ul>
  )
}

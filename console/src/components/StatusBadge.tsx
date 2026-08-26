import type { RunStatus } from '../types/api'
import { titleCaseStatus } from '../lib/format'
import styles from './StatusBadge.module.css'

const STATUS_VAR: Record<RunStatus, string> = {
  PENDING: 'pending',
  SCHEDULED: 'scheduled',
  RUNNING: 'running',
  SUCCEEDED: 'succeeded',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TIMED_OUT: 'timed-out',
}

export interface StatusBadgeProps {
  status: RunStatus
  className?: string
}

/** The single source of truth for run status color + label. Every table,
 * header, and chart legend renders status through this component (or reads
 * the same --status-* tokens) so colors never drift out of sync. */
export function StatusBadge({ status, className }: StatusBadgeProps) {
  const variant = STATUS_VAR[status]
  return (
    <span className={`badge ${styles.badge} ${styles[variant]} ${className ?? ''}`} data-status={status}>
      <span className={`badge-dot ${status === 'RUNNING' ? 'badge-dot-pulse' : ''}`} aria-hidden="true" />
      {titleCaseStatus(status)}
    </span>
  )
}

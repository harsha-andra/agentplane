import { AlertTriangle, Brain, GitBranch, Wrench } from 'lucide-react'
import type { Trace, TraceType } from '../types/api'
import { formatDuration } from '../lib/format'
import { CollapsibleJson } from './JsonView'
import { EmptyState } from './EmptyState'
import styles from './StepTimeline.module.css'

const TYPE_ICON: Record<TraceType, typeof Wrench> = {
  TOOL_CALL: Wrench,
  LLM_CALL: Brain,
  DECISION: GitBranch,
  ERROR: AlertTriangle,
}

function stepTitle(t: Trace): string {
  if (t.type === 'TOOL_CALL') return t.toolName ?? 'tool call'
  if (t.type === 'LLM_CALL') return String(t.payload.model ?? 'model call')
  if (t.type === 'DECISION') return 'Decision'
  return 'Error'
}

export interface StepTimelineProps {
  traces: Trace[]
}

export function StepTimeline({ traces }: StepTimelineProps) {
  if (traces.length === 0) {
    return <EmptyState title="No steps recorded" body="Steps appear once the run starts executing." />
  }

  const maxLatency = Math.max(...traces.map((t) => t.latencyMs), 1)

  return (
    <ol className={styles.list}>
      {traces.map((t) => {
        const Icon = TYPE_ICON[t.type]
        const isError = t.status === 'ERROR'
        const pct = Math.max(3, Math.round((t.latencyMs / maxLatency) * 100))
        return (
          <li key={t.id} className={styles.step}>
            <div className={styles.row}>
              <span className={`mono ${styles.seq}`}>{t.seq}</span>
              <span className={`${styles.typeIcon} ${isError ? styles.typeIconError : ''}`}>
                <Icon size={13} />
              </span>
              <div className={styles.name}>
                <div className={`${styles.nameTitle} truncate`}>{stepTitle(t)}</div>
                <div className={styles.nameSub}>{t.type.replace('_', ' ')}</div>
              </div>
              <div className={styles.latencyWrap}>
                <span className={styles.latencyBarTrack}>
                  <span
                    className={`${styles.latencyBarFill} ${isError ? styles.latencyBarFillError : ''}`}
                    style={{ width: `${pct}%` }}
                  />
                </span>
                <span className={`mono tabular ${styles.latencyValue}`}>{formatDuration(t.latencyMs)}</span>
              </div>
            </div>
            <div className={styles.payload}>
              <CollapsibleJson value={t.payload} summary={isError ? `Error payload: ${t.error}` : 'Payload'} />
            </div>
          </li>
        )
      })}
    </ol>
  )
}

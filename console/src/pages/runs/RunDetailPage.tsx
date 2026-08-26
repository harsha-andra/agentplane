import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { Ban, Bot, Box, Building2, Clock, RotateCw, Server } from 'lucide-react'
import { useCancelRunMutation, useRetryRunMutation, useRunQuery } from '../../api/runs'
import { useRunTracesQuery } from '../../api/traces'
import { StatusBadge } from '../../components/StatusBadge'
import { CopyableId } from '../../components/CopyableId'
import { LiveLogTail } from '../../components/LiveLogTail'
import { StepTimeline } from '../../components/StepTimeline'
import { JsonView } from '../../components/JsonView'
import { KeyValueGrid } from '../../components/KeyValueGrid'
import { EmptyState } from '../../components/EmptyState'
import { Skeleton } from '../../components/Skeleton'
import { formatDateTime, formatDuration, formatNumber, formatUsd } from '../../lib/format'
import type { RunStatus } from '../../types/api'
import styles from './RunDetailPage.module.css'

const ACTIVE_STATUSES: RunStatus[] = ['PENDING', 'SCHEDULED', 'RUNNING']
const RETRYABLE_STATUSES: RunStatus[] = ['FAILED', 'CANCELLED', 'TIMED_OUT']

/** durationMs is null until a run finishes (matches the API contract) — for
 * a RUNNING run we still want a duration to show, so tick a live elapsed
 * time client-side instead of showing a dash. */
function LiveElapsed({ startedAt }: { startedAt: string }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])
  return <>{formatDuration(now - new Date(startedAt).getTime())}</>
}

export function RunDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [pollMs, setPollMs] = useState<number | false>(4000)

  const { data: run, isLoading, isError, error, refetch } = useRunQuery(id, { refetchInterval: pollMs })
  const { data: traces, isLoading: tracesLoading } = useRunTracesQuery(id)
  const cancelMutation = useCancelRunMutation()
  const retryMutation = useRetryRunMutation()

  useEffect(() => {
    if (!run) return
    setPollMs(ACTIVE_STATUSES.includes(run.status) ? 4000 : false)
  }, [run?.status])

  if (isError) {
    return (
      <EmptyState
        title="Couldn't load this run"
        body={error instanceof Error ? error.message : 'It may not exist.'}
        action={
          <button type="button" className="btn btn-sm" onClick={() => navigate('/runs')}>
            Back to runs
          </button>
        }
      />
    )
  }

  if (isLoading || !run) {
    return (
      <div>
        <div className="card" style={{ padding: 'var(--space-5)', marginBottom: 'var(--space-4)' }}>
          <Skeleton height={28} width="40%" />
          <div style={{ marginTop: 12 }}>
            <Skeleton height={14} width="60%" />
          </div>
        </div>
        <div className={styles.mainGrid}>
          <div className="card" style={{ height: 460 }}>
            <Skeleton height="100%" />
          </div>
          <div className={styles.sideStack}>
            <div className="card" style={{ height: 220 }}>
              <Skeleton height="100%" />
            </div>
          </div>
        </div>
      </div>
    )
  }

  const canCancel = ACTIVE_STATUSES.includes(run.status)
  const canRetry = RETRYABLE_STATUSES.includes(run.status)

  return (
    <div>
      <div className={`card ${styles.header}`}>
        <div className={styles.headerLeft}>
          <div className={styles.titleRow}>
            <StatusBadge status={run.status} />
            <CopyableId value={run.id} head={12} tail={4} className="mono" />
            {run.attempt > 1 && (
              <span className="pill" style={{ height: 22 }}>
                attempt {run.attempt}
              </span>
            )}
          </div>
          <div className={styles.metaRow}>
            <span className={styles.metaItem}>
              <Bot size={13} />
              <span className={styles.metaValue}>{run.agentName}</span>
            </span>
            <span className={styles.metaItem}>
              <Building2 size={13} />
              <span className={styles.metaValue}>{run.tenantName}</span>
            </span>
            <span className={styles.metaItem}>
              <Clock size={13} />
              <span className={`${styles.metaValue} mono tabular`}>
                {run.status === 'RUNNING' && run.startedAt ? <LiveElapsed startedAt={run.startedAt} /> : formatDuration(run.durationMs)}
              </span>
            </span>
            <span className={styles.metaItem}>
              <Server size={13} />
              <span className={`${styles.metaValue} mono`}>{run.nodeName ?? 'unassigned'}</span>
            </span>
            <span className={styles.metaItem}>
              <Box size={13} />
              <span className={`${styles.metaValue} mono`}>{run.k8sJobName}</span>
            </span>
          </div>
          {run.message && (
            <div style={{ fontSize: 'var(--text-xs)', color: run.status === 'FAILED' || run.status === 'TIMED_OUT' ? 'var(--status-failed)' : 'var(--text-tertiary)' }}>
              {run.message}
            </div>
          )}
        </div>
        <div className={styles.actions}>
          {canCancel && (
            <button type="button" className="btn btn-sm btn-danger" onClick={() => id && cancelMutation.mutate(id)} disabled={cancelMutation.isPending}>
              <Ban size={13} />
              {cancelMutation.isPending ? 'Cancelling…' : 'Cancel'}
            </button>
          )}
          {canRetry && (
            <button
              type="button"
              className="btn btn-sm"
              disabled={retryMutation.isPending}
              onClick={() => id && retryMutation.mutate(id, { onSuccess: (newRun) => navigate(`/runs/${newRun.id}`) })}
            >
              <RotateCw size={13} />
              {retryMutation.isPending ? 'Retrying…' : 'Retry'}
            </button>
          )}
          <button type="button" className="btn btn-sm" onClick={() => refetch()}>
            Refresh
          </button>
        </div>
      </div>

      <div className={styles.mainGrid}>
        <div className={`card ${styles.liveCard}`}>
          <div className="card-header">
            <span className="card-title">Live log tail</span>
          </div>
          <div className={`card-body ${styles.liveBody}`}>
            <LiveLogTail runId={run.id} />
          </div>
        </div>

        <div className={styles.sideStack}>
          <div className="card">
            <div className="card-header">
              <span className="card-title">Kubernetes</span>
            </div>
            <div className="card-body">
              <KeyValueGrid
                items={[
                  { label: 'Pod phase', value: run.podPhase ?? '—' },
                  { label: 'Exit code', value: run.exitCode ?? '—' },
                  { label: 'Restarts', value: run.restartCount },
                  { label: 'Node', value: run.nodeName ?? '—' },
                  { label: 'Namespace', value: run.namespace },
                  { label: 'CPU request', value: run.spec.resources.cpu },
                  { label: 'Memory request', value: run.spec.resources.memory },
                ]}
              />
            </div>
          </div>

          <div className="card">
            <div className="card-header">
              <span className="card-title">Token usage &amp; cost</span>
            </div>
            <div className="card-body">
              <KeyValueGrid
                items={[
                  { label: 'Model', value: run.spec.model },
                  { label: 'Prompt tokens', value: formatNumber(run.tokenUsage.prompt) },
                  { label: 'Completion tokens', value: formatNumber(run.tokenUsage.completion) },
                  { label: 'Total tokens', value: formatNumber(run.tokenUsage.total) },
                  { label: 'Estimated cost', value: formatUsd(run.costUsd) },
                ]}
              />
            </div>
          </div>
        </div>
      </div>

      <div className={styles.lowerGrid}>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Steps ({run.stepCount})</span>
          </div>
          <div className={`card-body ${styles.stepsBody}`}>
            {tracesLoading ? (
              <Skeleton height={200} />
            ) : (
              <StepTimeline traces={traces ?? []} />
            )}
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">Submitted spec</span>
            <span className="mono text-tertiary" style={{ fontSize: 'var(--text-2xs)' }}>
              {formatDateTime(run.createdAt)}
            </span>
          </div>
          <div className="card-body">
            <JsonView value={run.spec} />
          </div>
        </div>
      </div>
    </div>
  )
}

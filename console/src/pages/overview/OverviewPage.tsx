import { Activity, CircleCheck, Coins, Gauge, Layers } from 'lucide-react'
import { useOverviewQuery } from '../../api/analytics'
import { StatTile } from '../../components/StatTile'
import { RunsOverTimeChart } from '../../components/charts/RunsOverTimeChart'
import { ToolLatencyBarChart } from '../../components/charts/ToolLatencyBarChart'
import { EventFeed } from '../../components/EventFeed'
import { UtilizationBar } from '../../components/UtilizationBar'
import { EmptyState } from '../../components/EmptyState'
import { formatCompactNumber, formatDuration, formatPercent, formatUsd } from '../../lib/format'
import styles from './OverviewPage.module.css'
import { AlertTriangle } from 'lucide-react'

export function OverviewPage() {
  const { data, isLoading, isError, error, refetch } = useOverviewQuery()

  if (isError) {
    return (
      <EmptyState
        icon={<AlertTriangle size={28} strokeWidth={1.5} />}
        title="Couldn't load the overview"
        body={error instanceof Error ? error.message : 'Unknown error'}
        action={
          <button type="button" className="btn btn-sm" onClick={() => refetch()}>
            Retry
          </button>
        }
      />
    )
  }

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Overview</h1>
          <p className="page-subtitle">Fleet-wide status across all tenants, refreshed every 15s</p>
        </div>
      </div>

      <div className={styles.statGrid}>
        <StatTile
          label="Active runs"
          value={formatCompactNumber(data?.activeRuns)}
          hint="Currently RUNNING"
          icon={<Activity size={16} />}
          loading={isLoading}
        />
        <StatTile
          label="Queued / stream depth"
          value={data ? `${formatCompactNumber(data.queuedRuns)} / ${formatCompactNumber(data.streamDepth)}` : undefined}
          hint="Pending+scheduled / buffered events"
          icon={<Layers size={16} />}
          loading={isLoading}
        />
        <StatTile
          label="24h success rate"
          value={data ? formatPercent(data.successRate24h, 0) : undefined}
          hint="Of runs finished in the last 24h"
          icon={<CircleCheck size={16} />}
          tone={data && data.successRate24h < 0.9 ? 'warning' : 'positive'}
          loading={isLoading}
        />
        <StatTile
          label="p95 run duration"
          value={data ? formatDuration(data.p95DurationMs) : undefined}
          hint="Last 24h, finished runs"
          icon={<Gauge size={16} />}
          loading={isLoading}
        />
        <StatTile
          label="24h token spend"
          value={data ? formatUsd(data.tokenSpend24h) : undefined}
          hint="Estimated model cost"
          icon={<Coins size={16} />}
          loading={isLoading}
        />
      </div>

      <div className={styles.chartsGrid}>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Runs over time (24h)</span>
          </div>
          <div className="card-body">
            {isLoading || !data ? (
              <div style={{ height: 240 }} className="skeleton" />
            ) : (
              <RunsOverTimeChart data={data.runsOverTime} />
            )}
          </div>
        </div>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Tool latency (p95)</span>
          </div>
          <div className="card-body">
            {isLoading || !data ? (
              <div style={{ height: 240 }} className="skeleton" />
            ) : data.toolLatency.length === 0 ? (
              <EmptyState title="No tool calls yet" />
            ) : (
              <ToolLatencyBarChart data={data.toolLatency} />
            )}
          </div>
        </div>
      </div>

      <div className={styles.bottomGrid}>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Live cluster events</span>
          </div>
          <div className="card-body" style={{ padding: 'var(--space-2) var(--space-4)' }}>
            {isLoading || !data ? <div style={{ height: 300 }} className="skeleton" /> : <EventFeed events={data.recentEvents} />}
          </div>
        </div>
        <div className="card">
          <div className="card-header">
            <span className="card-title">Tenant quota utilization</span>
          </div>
          <div className="card-body">
            {isLoading || !data ? (
              <div style={{ height: 200 }} className="skeleton" />
            ) : data.tenantUtilization.length === 0 ? (
              <EmptyState title="No tenants configured" />
            ) : (
              <div className={styles.tenantList}>
                {data.tenantUtilization.map((t) => (
                  <UtilizationBar key={t.tenantName} label={t.tenantName} value={t.activeRuns} max={t.maxConcurrentRuns} />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

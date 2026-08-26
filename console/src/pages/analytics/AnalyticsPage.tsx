import { useState } from 'react'
import { useToolLatencyQuery } from '../../api/analytics'
import { ToolLatencyBarChart } from '../../components/charts/ToolLatencyBarChart'
import { EmptyState } from '../../components/EmptyState'
import { formatDuration, formatPercent } from '../../lib/format'

const RANGE_OPTIONS = [
  { value: 7, label: 'Last 7 days' },
  { value: 14, label: 'Last 14 days' },
  { value: 30, label: 'Last 30 days' },
]

export function AnalyticsPage() {
  const [days, setDays] = useState(7)
  const { data, isLoading, isError, error } = useToolLatencyQuery(days)

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Analytics</h1>
          <p className="page-subtitle">Tool performance across the fleet</p>
        </div>
        <select className="select" style={{ width: 'auto' }} value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {RANGE_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </div>

      <div className="card" style={{ marginBottom: 'var(--space-4)' }}>
        <div className="card-header">
          <span className="card-title">p95 latency by tool (top 8)</span>
        </div>
        <div className="card-body">
          {isLoading ? (
            <div style={{ height: 260 }} className="skeleton" />
          ) : isError ? (
            <EmptyState title="Couldn't load tool latency" body={error instanceof Error ? error.message : undefined} />
          ) : data && data.length > 0 ? (
            <ToolLatencyBarChart data={data} />
          ) : (
            <EmptyState title="No tool calls in this window" />
          )}
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">All tools</span>
        </div>
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Tool</th>
                <th>Calls</th>
                <th>Avg latency</th>
                <th>p95 latency</th>
                <th>Error rate</th>
              </tr>
            </thead>
            <tbody>
              {(data ?? [])
                .slice()
                .sort((a, b) => b.callCount - a.callCount)
                .map((t) => (
                  <tr key={t.toolName}>
                    <td className="mono">{t.toolName}</td>
                    <td className="mono tabular">{t.callCount}</td>
                    <td className="mono tabular">{formatDuration(t.avgLatencyMs)}</td>
                    <td className="mono tabular">{formatDuration(t.p95LatencyMs)}</td>
                    <td className="mono tabular" style={{ color: t.errorRate > 0.05 ? 'var(--negative)' : undefined }}>
                      {formatPercent(t.errorRate, 1)}
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
        {!isLoading && (data ?? []).length === 0 && <EmptyState title="No data" />}
      </div>
    </div>
  )
}

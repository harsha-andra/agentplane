import { getClusterSummary } from '../../mocks/health'

export function ClusterHealthPill() {
  const summary = getClusterSummary()
  const allHealthy = summary.healthy === summary.total
  const color = allHealthy ? 'var(--positive)' : summary.healthy === 0 ? 'var(--negative)' : 'var(--warning)'

  return (
    <span className="pill" title={`${summary.healthy}/${summary.total} backends healthy`}>
      <span
        className={`badge-dot ${allHealthy ? 'badge-dot-pulse' : ''}`}
        style={{ color }}
        aria-hidden="true"
      />
      <span className="truncate pill-label">{summary.label}</span>
    </span>
  )
}

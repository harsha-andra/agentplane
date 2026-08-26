// Backing data for the cluster-health pill and the Settings page's
// connected-backends panel.

export type BackendStatus = 'healthy' | 'degraded' | 'down'

export interface BackendHealth {
  name: string
  kind: string
  status: BackendStatus
  version: string
  latencyMs: number
  detail: string
}

export function getBackendHealth(): BackendHealth[] {
  return [
    { name: 'Postgres (control-plane)', kind: 'postgres', status: 'healthy', version: '16.4', latencyMs: 2.1, detail: 'primary + 2 read replicas, streaming replication lag < 50ms' },
    { name: 'MongoDB (run events archive)', kind: 'mongodb', status: 'healthy', version: '7.0.12', latencyMs: 4.8, detail: '3-node replica set, oplog window 36h' },
    { name: 'Redis (scheduler queue)', kind: 'redis', status: 'healthy', version: '7.2.5', latencyMs: 0.6, detail: 'cluster mode, 6 shards' },
    { name: 'Kubernetes API', kind: 'kubernetes', status: 'healthy', version: '1.30', latencyMs: 12.3, detail: '3 control-plane nodes, 42 worker nodes across 3 pools' },
  ]
}

export function getClusterSummary(): { healthy: number; total: number; label: string } {
  const backends = getBackendHealth()
  const healthy = backends.filter((b) => b.status === 'healthy').length
  const total = backends.length
  const label = healthy === total ? 'All systems operational' : healthy === 0 ? 'Major outage' : 'Degraded performance'
  return { healthy, total, label }
}

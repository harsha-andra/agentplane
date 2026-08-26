import { CircleCheck, CircleX, Database, Server, TriangleAlert } from 'lucide-react'
import { getBackendHealth, type BackendStatus } from '../../mocks/health'
import { KeyValueGrid } from '../../components/KeyValueGrid'
import { API_BASE, APP_NAME, DEMO_MODE } from '../../config'

const STATUS_ICON: Record<BackendStatus, typeof CircleCheck> = {
  healthy: CircleCheck,
  degraded: TriangleAlert,
  down: CircleX,
}

const STATUS_COLOR: Record<BackendStatus, string> = {
  healthy: 'var(--positive)',
  degraded: 'var(--warning)',
  down: 'var(--negative)',
}

function iconFor(kind: string) {
  if (kind === 'kubernetes') return Server
  return Database
}

export function SettingsPage() {
  const backends = getBackendHealth()

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Settings</h1>
          <p className="page-subtitle">Read-only control-plane configuration</p>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 'var(--space-4)' }}>
        <div className="card-header">
          <span className="card-title">Connected backends</span>
        </div>
        <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
          {backends.map((b) => {
            const StatusIcon = STATUS_ICON[b.status]
            const Icon = iconFor(b.kind)
            return (
              <div key={b.name} style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <span
                  style={{
                    width: 36,
                    height: 36,
                    borderRadius: 'var(--radius-md)',
                    background: 'var(--bg-inset)',
                    color: 'var(--text-secondary)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                  }}
                >
                  <Icon size={16} />
                </span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontWeight: 500 }}>{b.name}</span>
                    <span className="mono text-tertiary" style={{ fontSize: 'var(--text-2xs)' }}>
                      v{b.version}
                    </span>
                  </div>
                  <div className="text-tertiary truncate" style={{ fontSize: 'var(--text-xs)' }}>
                    {b.detail}
                  </div>
                </div>
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 5, color: STATUS_COLOR[b.status], fontSize: 'var(--text-xs)', fontWeight: 500, justifyContent: 'flex-end' }}>
                    <StatusIcon size={13} />
                    {b.status}
                  </div>
                  <div className="mono text-tertiary" style={{ fontSize: 'var(--text-2xs)' }}>
                    {b.latencyMs.toFixed(1)}ms
                  </div>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">Console runtime</span>
        </div>
        <div className="card-body">
          <KeyValueGrid
            items={[
              { label: 'Application', value: APP_NAME },
              { label: 'Console version', value: 'v0.1.0' },
              { label: 'Demo mode', value: DEMO_MODE ? 'on (seeded mock data)' : 'off (live API)' },
              { label: 'API base', value: API_BASE },
              { label: 'Control plane', value: 'agentplane-control-plane v2.4.1' },
              { label: 'Kubernetes API version', value: '1.30' },
            ]}
          />
        </div>
      </div>
    </div>
  )
}

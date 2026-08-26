export interface ChartTooltipPayloadItem {
  color?: string
  name?: string | number
  value?: string | number
  dataKey?: string | number
}

export interface ChartTooltipProps {
  active?: boolean
  label?: string | number
  payload?: ChartTooltipPayloadItem[]
  labelFormatter?: (label: string | number) => string
  valueFormatter?: (value: string | number, name: string | number | undefined) => string
}

/** Shared Recharts tooltip renderer so every chart in the console matches
 * the same card/typography treatment instead of Recharts' default. */
export function ChartTooltip({ active, label, payload, labelFormatter, valueFormatter }: ChartTooltipProps) {
  if (!active || !payload || payload.length === 0) return null

  return (
    <div
      className="card"
      style={{
        padding: '8px 10px',
        boxShadow: 'var(--shadow-md)',
        fontSize: 'var(--text-xs)',
        minWidth: 140,
      }}
    >
      {label !== undefined && (
        <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--text-secondary)' }}>
          {labelFormatter ? labelFormatter(label) : label}
        </div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {payload.map((p, i) => (
          <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: p.color, flexShrink: 0 }} />
            <span style={{ color: 'var(--text-tertiary)' }}>{p.name}</span>
            <span className="mono" style={{ marginLeft: 'auto', fontWeight: 600, color: 'var(--text-primary)' }}>
              {valueFormatter ? valueFormatter(p.value ?? '', p.name) : p.value}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

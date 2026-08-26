export interface UtilizationBarProps {
  label: string
  value: number
  max: number
}

export function UtilizationBar({ label, value, max }: UtilizationBarProps) {
  const pct = max > 0 ? Math.min(100, (value / max) * 100) : 0
  const tone = pct >= 90 ? 'meter-fill-danger' : pct >= 70 ? 'meter-fill-warn' : ''

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginBottom: 5 }}>
        <span className="truncate">{label}</span>
        <span className="mono text-tertiary" style={{ flexShrink: 0 }}>
          {value}/{max}
        </span>
      </div>
      <div className="meter" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100} aria-label={`${label} utilization`}>
        <div className={`meter-fill ${tone}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

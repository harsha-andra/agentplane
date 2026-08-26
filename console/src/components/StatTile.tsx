import type { ReactNode } from 'react'
import styles from './StatTile.module.css'

export interface StatTileProps {
  label: string
  value: ReactNode
  hint?: ReactNode
  icon?: ReactNode
  tone?: 'default' | 'positive' | 'negative' | 'warning'
  loading?: boolean
}

export function StatTile({ label, value, hint, icon, tone = 'default', loading }: StatTileProps) {
  return (
    <div className={`card ${styles.tile}`}>
      <div className={styles.top}>
        <span className={styles.label}>{label}</span>
        {icon ? <span className={`${styles.icon} ${styles[`icon-${tone}`]}`}>{icon}</span> : null}
      </div>
      {loading ? (
        <div className={`skeleton ${styles.valueSkeleton}`}>0</div>
      ) : (
        <div className={`mono ${styles.value}`}>{value}</div>
      )}
      {hint ? <div className={styles.hint}>{hint}</div> : null}
    </div>
  )
}

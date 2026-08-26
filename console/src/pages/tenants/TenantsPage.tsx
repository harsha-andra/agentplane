import { useState } from 'react'
import { Building2, Cpu, MemoryStick, Plus } from 'lucide-react'
import { useTenantsQuery } from '../../api/tenants'
import { UtilizationBar } from '../../components/UtilizationBar'
import { EmptyState } from '../../components/EmptyState'
import { Skeleton } from '../../components/Skeleton'
import { CreateTenantModal } from './CreateTenantModal'
import styles from './TenantsPage.module.css'

export function TenantsPage() {
  const { data, isLoading, isError, error } = useTenantsQuery({ refetchInterval: 10_000 })
  const [showCreate, setShowCreate] = useState(false)

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Tenants</h1>
          <p className="page-subtitle">{data ? `${data.length} tenants` : 'Loading…'}</p>
        </div>
        <button type="button" className="btn btn-sm btn-primary" onClick={() => setShowCreate(true)}>
          <Plus size={14} />
          New tenant
        </button>
      </div>

      {isError ? (
        <EmptyState title="Couldn't load tenants" body={error instanceof Error ? error.message : 'Unknown error'} />
      ) : isLoading ? (
        <div className={styles.grid}>
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className={`card ${styles.card}`}>
              <Skeleton height={80} />
            </div>
          ))}
        </div>
      ) : data && data.length > 0 ? (
        <div className={styles.grid}>
          {data.map((t) => (
            <div key={t.id} className={`card ${styles.card}`}>
              <div className={styles.cardHead}>
                <div className="truncate">
                  <div className={`${styles.name} truncate`}>{t.name}</div>
                  <div className={`${styles.slug} mono`}>{t.slug}</div>
                </div>
                <span className={styles.icon}>
                  <Building2 size={16} />
                </span>
              </div>

              <div className="mono text-tertiary" style={{ fontSize: 'var(--text-xs)' }}>
                {t.namespace}
              </div>

              <div className={styles.quotaRow}>
                <div>
                  <span className={styles.quotaLabel}>
                    <Cpu size={11} style={{ display: 'inline', marginRight: 4, verticalAlign: -1 }} />
                    CPU quota
                  </span>
                  <span className={`${styles.quotaValue} mono`}>{t.quotaCpu} vCPU</span>
                </div>
                <div>
                  <span className={styles.quotaLabel}>
                    <MemoryStick size={11} style={{ display: 'inline', marginRight: 4, verticalAlign: -1 }} />
                    Memory quota
                  </span>
                  <span className={`${styles.quotaValue} mono`}>{t.quotaMemory}</span>
                </div>
              </div>

              <UtilizationBar label="Concurrent runs" value={t.activeRuns} max={t.maxConcurrentRuns} />
            </div>
          ))}
        </div>
      ) : (
        <EmptyState title="No tenants yet" body="Create one to get started." />
      )}

      {showCreate && <CreateTenantModal onClose={() => setShowCreate(false)} />}
    </div>
  )
}

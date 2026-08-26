import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { RefreshCw, Search } from 'lucide-react'
import { useRunsQuery } from '../../api/runs'
import { useTenantsQuery } from '../../api/tenants'
import { StatusBadge } from '../../components/StatusBadge'
import { CopyableId } from '../../components/CopyableId'
import { Pagination } from '../../components/Pagination'
import { SkeletonTableRows } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { formatDuration, formatRelativeTime } from '../../lib/format'
import type { RunStatus } from '../../types/api'
import { StatusFilter } from './StatusFilter'
import styles from './RunsListPage.module.css'

const PAGE_SIZE = 20

export function RunsListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  const page = Number(searchParams.get('page') ?? '0') || 0
  const tenantId = searchParams.get('tenantId') ?? ''
  const q = searchParams.get('q') ?? ''
  const selectedStatuses = useMemo<RunStatus[]>(() => {
    const raw = searchParams.get('status')
    return raw ? (raw.split(',') as RunStatus[]) : []
  }, [searchParams])

  const [qInput, setQInput] = useState(q)
  const debouncedQ = useDebouncedValue(qInput, 300)
  const [autoRefresh, setAutoRefresh] = useState(true)

  const updateFilter = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    for (const [k, v] of Object.entries(patch)) {
      if (v) next.set(k, v)
      else next.delete(k)
    }
    if (!('page' in patch)) next.delete('page')
    setSearchParams(next)
  }

  // Reflect the debounced free-text search into the URL (shareable filter
  // state), resetting to page 0 whenever it actually changes.
  useEffect(() => {
    if (debouncedQ === q) return
    updateFilter({ q: debouncedQ || undefined })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedQ])

  // Reflect external URL changes (e.g. the global search box) back into the
  // input — without this, navigating to /runs?q=foo while already mounted
  // wouldn't update the field.
  useEffect(() => {
    setQInput(q)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q])

  const { data, isLoading, isFetching, isError, error } = useRunsQuery(
    {
      page,
      size: PAGE_SIZE,
      status: selectedStatuses.length ? selectedStatuses : undefined,
      tenantId: tenantId || undefined,
      q: q || undefined,
    },
    { refetchInterval: autoRefresh ? 5000 : false },
  )

  const { data: tenants } = useTenantsQuery()

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Runs</h1>
          <p className="page-subtitle">{data ? `${data.totalElements} total` : 'Loading…'}</p>
        </div>
      </div>

      <div className={styles.filters}>
        <StatusFilter selected={selectedStatuses} onChange={(next) => updateFilter({ status: next.length ? next.join(',') : undefined })} />
        <select
          className="select"
          style={{ width: 'auto', minWidth: 170 }}
          value={tenantId}
          onChange={(e) => updateFilter({ tenantId: e.target.value || undefined })}
          aria-label="Filter by tenant"
        >
          <option value="">All tenants</option>
          {tenants?.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        <div className={`search-box ${styles.searchField}`}>
          <Search size={14} aria-hidden="true" />
          <input
            placeholder="Search run id, agent, job…"
            value={qInput}
            onChange={(e) => setQInput(e.target.value)}
            aria-label="Search runs"
          />
        </div>
        <div className={styles.spacer} />
        <button
          type="button"
          className={`btn btn-sm ${autoRefresh ? 'btn-primary' : ''}`}
          onClick={() => setAutoRefresh((v) => !v)}
          aria-pressed={autoRefresh}
        >
          <RefreshCw size={14} className={autoRefresh && isFetching ? 'spin' : ''} />
          Auto-refresh {autoRefresh ? 'on' : 'off'}
        </button>
      </div>

      <div className="card">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Run ID</th>
                <th>Tenant</th>
                <th>Agent</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Started</th>
                <th>Attempt</th>
                <th>K8s job</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <SkeletonTableRows rows={PAGE_SIZE} cols={8} />
              ) : data && data.content.length > 0 ? (
                data.content.map((run) => (
                  <tr key={run.id} className="table-row-link" onClick={() => navigate(`/runs/${run.id}`)}>
                    <td className={styles.idCell}>
                      <CopyableId value={run.id} />
                    </td>
                    <td className="truncate">{run.tenantName}</td>
                    <td className="mono">{run.agentName}</td>
                    <td>
                      <StatusBadge status={run.status} />
                    </td>
                    <td className="mono tabular">{formatDuration(run.durationMs)}</td>
                    <td className="mono tabular" title={run.startedAt ?? undefined}>
                      {formatRelativeTime(run.startedAt)}
                    </td>
                    <td className="mono tabular">{run.attempt}</td>
                    <td className={`mono ${styles.jobCell}`}>
                      <span className="truncate">{run.k8sJobName}</span>
                    </td>
                  </tr>
                ))
              ) : null}
            </tbody>
          </table>
        </div>

        {!isLoading && !isError && data && data.content.length === 0 && (
          <EmptyState title="No runs match these filters" body="Try clearing the status or tenant filter." />
        )}
        {isError && <EmptyState title="Couldn't load runs" body={error instanceof Error ? error.message : 'Unknown error'} />}
        {data && data.content.length > 0 && (
          <Pagination
            page={data.number}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            pageSize={data.size}
            onPageChange={(p) => updateFilter({ page: String(p) })}
          />
        )}
      </div>
    </div>
  )
}

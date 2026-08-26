import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, Search } from 'lucide-react'
import { useTraceListQuery } from '../../api/traces'
import { LatencyDistributionChart, type LatencyBucket } from '../../components/charts/LatencyDistributionChart'
import { Pagination } from '../../components/Pagination'
import { SkeletonTableRows } from '../../components/Skeleton'
import { EmptyState } from '../../components/EmptyState'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { formatDateTime, formatDuration, truncateId } from '../../lib/format'
import type { TraceRow } from '../../types/api'
import styles from './TracesPage.module.css'

const WINDOW_OPTIONS = [
  { value: '1h', label: 'Last hour', ms: 3600e3 },
  { value: '6h', label: 'Last 6h', ms: 6 * 3600e3 },
  { value: '24h', label: 'Last 24h', ms: 24 * 3600e3 },
  { value: '7d', label: 'Last 7 days', ms: 7 * 24 * 3600e3 },
  { value: '30d', label: 'Last 30 days', ms: 30 * 24 * 3600e3 },
  { value: 'all', label: 'All time', ms: null },
] as const

const BUCKET_DEFS = [
  { max: 50, label: '<50ms' },
  { max: 100, label: '50-100ms' },
  { max: 250, label: '100-250ms' },
  { max: 500, label: '250-500ms' },
  { max: 1000, label: '500ms-1s' },
  { max: 2500, label: '1-2.5s' },
  { max: 5000, label: '2.5-5s' },
  { max: Infinity, label: '>5s' },
]

function computeBuckets(rows: TraceRow[]): LatencyBucket[] {
  const counts = BUCKET_DEFS.map((b) => ({ label: b.label, count: 0 }))
  for (const r of rows) {
    const idx = BUCKET_DEFS.findIndex((b) => r.latencyMs < b.max)
    counts[idx === -1 ? counts.length - 1 : idx].count += 1
  }
  return counts
}

const PAGE_SIZE = 25

type SortField = 'time' | 'latency'

export function TracesPage() {
  const [toolName, setToolName] = useState('')
  const [windowSel, setWindowSel] = useState<(typeof WINDOW_OPTIONS)[number]['value']>('24h')
  const [qInput, setQInput] = useState('')
  const q = useDebouncedValue(qInput, 300)
  const [sortField, setSortField] = useState<SortField>('time')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [page, setPage] = useState(0)

  const fromIso = useMemo(() => {
    const opt = WINDOW_OPTIONS.find((o) => o.value === windowSel)
    return opt?.ms ? new Date(Date.now() - opt.ms).toISOString() : undefined
  }, [windowSel])

  const { data, isLoading, isError, error } = useTraceListQuery({ from: fromIso, q: q || undefined, page: 0, size: 500 })

  const toolOptions = useMemo(() => {
    const set = new Set<string>()
    data?.content.forEach((r) => {
      if (r.toolName) set.add(r.toolName)
    })
    return Array.from(set).sort()
  }, [data])

  const filteredSorted = useMemo(() => {
    let rows = data?.content ?? []
    if (toolName) rows = rows.filter((r) => r.toolName === toolName)
    const dir = sortDir === 'asc' ? 1 : -1
    rows = [...rows].sort((a, b) =>
      sortField === 'latency'
        ? (a.latencyMs - b.latencyMs) * dir
        : (new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()) * dir,
    )
    return rows
  }, [data, toolName, sortField, sortDir])

  useEffect(() => {
    setPage(0)
  }, [toolName, windowSel, q, sortField, sortDir])

  const totalElements = filteredSorted.length
  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE))
  const pageRows = filteredSorted.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE)
  const buckets = useMemo(() => computeBuckets(filteredSorted), [filteredSorted])

  const toggleSort = (field: SortField) => {
    if (field === sortField) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    else {
      setSortField(field)
      setSortDir('desc')
    }
  }

  const SortIcon = sortDir === 'asc' ? ArrowUp : ArrowDown

  return (
    <div>
      <div className="page-header">
        <div>
          <h1 className="page-title">Traces</h1>
          <p className="page-subtitle">{isLoading ? 'Loading…' : `${totalElements} tool/model calls in view`}</p>
        </div>
      </div>

      <div className={styles.filters}>
        <select className="select" style={{ width: 'auto', minWidth: 150 }} value={toolName} onChange={(e) => setToolName(e.target.value)} aria-label="Filter by tool">
          <option value="">All tools</option>
          {toolOptions.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
        <select className="select" style={{ width: 'auto', minWidth: 140 }} value={windowSel} onChange={(e) => setWindowSel(e.target.value as typeof windowSel)} aria-label="Time window">
          {WINDOW_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
        <div className={`search-box ${styles.searchField}`}>
          <Search size={14} aria-hidden="true" />
          <input placeholder="Search run id, tenant, agent…" value={qInput} onChange={(e) => setQInput(e.target.value)} aria-label="Search traces" />
        </div>
      </div>

      <div className={`card ${styles.chartCard}`}>
        <div className="card-header">
          <span className="card-title">Latency distribution</span>
        </div>
        <div className="card-body">
          {isLoading ? <div style={{ height: 240 }} className="skeleton" /> : <LatencyDistributionChart data={buckets} />}
        </div>
      </div>

      <div className="card">
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>
                  <button type="button" className={`${styles.sortBtn}`} style={{ background: 'none', border: 'none', color: 'inherit', font: 'inherit', cursor: 'pointer' }} onClick={() => toggleSort('time')}>
                    Time {sortField === 'time' && <SortIcon size={11} />}
                  </button>
                </th>
                <th>Run</th>
                <th>Tenant</th>
                <th>Agent</th>
                <th>Type</th>
                <th>Tool</th>
                <th>
                  <button type="button" style={{ background: 'none', border: 'none', color: 'inherit', font: 'inherit', cursor: 'pointer' }} onClick={() => toggleSort('latency')}>
                    Latency {sortField === 'latency' && <SortIcon size={11} />}
                  </button>
                </th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {isLoading ? (
                <SkeletonTableRows rows={PAGE_SIZE} cols={8} />
              ) : (
                pageRows.map((t) => (
                  <tr key={t.id}>
                    <td className="mono tabular" title={formatDateTime(t.startedAt)}>
                      {formatDateTime(t.startedAt)}
                    </td>
                    <td className="mono">
                      <Link to={`/runs/${t.runId}`} style={{ color: 'var(--accent-strong)' }} title={t.runId}>
                        {truncateId(t.runId, 8, 0)}
                      </Link>
                    </td>
                    <td className="truncate">{t.tenantName}</td>
                    <td className="mono">{t.agentName}</td>
                    <td>{t.type.replace('_', ' ')}</td>
                    <td className="mono">{t.toolName ?? '—'}</td>
                    <td className="mono tabular">{formatDuration(t.latencyMs)}</td>
                    <td style={{ color: t.status === 'ERROR' ? 'var(--negative)' : 'var(--positive)', fontWeight: 500 }}>{t.status}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {isError && <EmptyState title="Couldn't load traces" body={error instanceof Error ? error.message : 'Unknown error'} />}
        {!isLoading && !isError && pageRows.length === 0 && <EmptyState title="No traces match these filters" />}
        {!isLoading && pageRows.length > 0 && (
          <Pagination page={page} totalPages={totalPages} totalElements={totalElements} pageSize={PAGE_SIZE} onPageChange={setPage} />
        )}
      </div>
    </div>
  )
}

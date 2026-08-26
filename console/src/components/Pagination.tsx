import { ChevronLeft, ChevronRight } from 'lucide-react'

export interface PaginationProps {
  page: number // 0-based
  totalPages: number
  totalElements: number
  pageSize: number
  onPageChange: (page: number) => void
}

export function Pagination({ page, totalPages, totalElements, pageSize, onPageChange }: PaginationProps) {
  const start = totalElements === 0 ? 0 : page * pageSize + 1
  const end = Math.min(totalElements, (page + 1) * pageSize)

  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '10px 4px', flexWrap: 'wrap' }}>
      <span className="text-tertiary mono" style={{ fontSize: 'var(--text-xs)' }}>
        {totalElements === 0 ? 'No results' : `${start}–${end} of ${totalElements}`}
      </span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <button
          type="button"
          className="btn btn-sm btn-icon"
          disabled={page <= 0}
          onClick={() => onPageChange(page - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft size={14} />
        </button>
        <span className="mono text-secondary" style={{ fontSize: 'var(--text-xs)', minWidth: 64, textAlign: 'center' }}>
          Page {totalPages === 0 ? 0 : page + 1} / {totalPages}
        </span>
        <button
          type="button"
          className="btn btn-sm btn-icon"
          disabled={page >= totalPages - 1}
          onClick={() => onPageChange(page + 1)}
          aria-label="Next page"
        >
          <ChevronRight size={14} />
        </button>
      </div>
    </div>
  )
}

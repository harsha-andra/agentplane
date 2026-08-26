import { useEffect, useRef, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { StatusBadge } from '../../components/StatusBadge'
import type { RunStatus } from '../../types/api'

export const ALL_STATUSES: RunStatus[] = ['PENDING', 'SCHEDULED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']

export interface StatusFilterProps {
  selected: RunStatus[]
  onChange: (next: RunStatus[]) => void
}

export function StatusFilter({ selected, onChange }: StatusFilterProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDocClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [open])

  const toggle = (s: RunStatus) => {
    onChange(selected.includes(s) ? selected.filter((x) => x !== s) : [...selected, s])
  }

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button type="button" className="btn btn-sm" onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="true">
        Status{selected.length > 0 ? ` (${selected.length})` : ''}
        <ChevronDown size={14} />
      </button>
      {open && (
        <div className="card" style={{ position: 'absolute', top: 'calc(100% + 6px)', left: 0, zIndex: 20, minWidth: 190, padding: 6 }}>
          {ALL_STATUSES.map((s) => (
            <label key={s} className="checkbox-row">
              <input type="checkbox" checked={selected.includes(s)} onChange={() => toggle(s)} />
              <StatusBadge status={s} />
            </label>
          ))}
          {selected.length > 0 && (
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginTop: 4 }} onClick={() => onChange([])}>
              Clear
            </button>
          )}
        </div>
      )}
    </div>
  )
}

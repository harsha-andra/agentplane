import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Search } from 'lucide-react'

export function GlobalSearch() {
  const [value, setValue] = useState('')
  const navigate = useNavigate()

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const q = value.trim()
    navigate(q ? `/runs?q=${encodeURIComponent(q)}` : '/runs')
  }

  return (
    <form className="search-box" role="search" onSubmit={onSubmit}>
      <Search size={14} aria-hidden="true" />
      <input
        type="search"
        placeholder="Search runs, tenants, agents…"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        aria-label="Global search"
      />
      <span className="kbd" aria-hidden="true">
        /
      </span>
    </form>
  )
}

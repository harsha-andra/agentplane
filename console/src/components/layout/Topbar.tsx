import { Menu } from 'lucide-react'
import { ClusterHealthPill } from './ClusterHealthPill'
import { EnvironmentSelector } from './EnvironmentSelector'
import { GlobalSearch } from './GlobalSearch'
import { ThemeToggle } from './ThemeToggle'

export interface TopbarProps {
  onMenuClick: () => void
}

export function Topbar({ onMenuClick }: TopbarProps) {
  return (
    <header className="topbar">
      <button
        type="button"
        className="btn btn-icon btn-ghost topbar-menu-btn"
        onClick={onMenuClick}
        aria-label="Toggle navigation"
      >
        <Menu size={18} />
      </button>
      <EnvironmentSelector />
      <ClusterHealthPill />
      <div className="topbar-search">
        <GlobalSearch />
      </div>
      <div className="topbar-actions">
        <ThemeToggle />
      </div>
    </header>
  )
}

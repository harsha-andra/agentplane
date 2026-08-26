import { NavLink } from 'react-router-dom'
import { BarChart3, Building2, LayoutDashboard, ListTree, Settings, Waypoints } from 'lucide-react'
import { APP_NAME } from '../../config'

const NAV_ITEMS = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/runs', label: 'Runs', icon: ListTree, end: false },
  { to: '/traces', label: 'Traces', icon: Waypoints, end: false },
  { to: '/tenants', label: 'Tenants', icon: Building2, end: false },
  { to: '/analytics', label: 'Analytics', icon: BarChart3, end: false },
  { to: '/settings', label: 'Settings', icon: Settings, end: false },
] as const

export interface SidebarProps {
  open: boolean
  onNavigate: () => void
}

export function Sidebar({ open, onNavigate }: SidebarProps) {
  return (
    <>
      <aside className={`sidebar ${open ? 'sidebar-open' : ''}`} aria-label="Primary">
        <div className="sidebar-brand">
          <span className="sidebar-brand-mark" aria-hidden="true">
            A
          </span>
          <span className="sidebar-brand-name">{APP_NAME}</span>
        </div>
        <nav className="sidebar-nav">
          {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
            <NavLink
              key={to}
              to={to}
              end={end}
              onClick={onNavigate}
              className={({ isActive }) => `nav-link ${isActive ? 'nav-link-active' : ''}`}
            >
              <Icon size={16} strokeWidth={2} />
              {label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer mono">console v0.1.0</div>
      </aside>
      <div
        className={`sidebar-backdrop ${open ? 'sidebar-backdrop-visible' : ''}`}
        onClick={onNavigate}
        aria-hidden="true"
      />
    </>
  )
}

import type { ReactNode } from 'react'
import { Inbox } from 'lucide-react'

export interface EmptyStateProps {
  icon?: ReactNode
  title: string
  body?: ReactNode
  action?: ReactNode
}

export function EmptyState({ icon, title, body, action }: EmptyStateProps) {
  return (
    <div className="empty-state" role="status">
      <span className="empty-state-icon">{icon ?? <Inbox size={28} strokeWidth={1.5} />}</span>
      <div className="empty-state-title">{title}</div>
      {body ? <div className="empty-state-body">{body}</div> : null}
      {action}
    </div>
  )
}

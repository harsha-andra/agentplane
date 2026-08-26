import { Link } from 'react-router-dom'
import { SearchX } from 'lucide-react'
import { EmptyState } from '../components/EmptyState'

export function NotFoundPage() {
  return (
    <EmptyState
      icon={<SearchX size={28} strokeWidth={1.5} />}
      title="Page not found"
      body="That route doesn't exist in the console."
      action={
        <Link to="/" className="btn btn-sm">
          Back to overview
        </Link>
      }
    />
  )
}

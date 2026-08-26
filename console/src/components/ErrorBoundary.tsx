import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'

export interface ErrorBoundaryProps {
  children: ReactNode
  fallbackTitle?: string
}

interface ErrorBoundaryState {
  error: Error | null
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // A real deployment would forward this to an error-tracking backend.
    console.error('AGENTPLANE console crashed:', error, info.componentStack)
  }

  private reset = () => this.setState({ error: null })

  render() {
    if (this.state.error) {
      return (
        <div className="empty-state" role="alert">
          <span className="empty-state-icon" style={{ color: 'var(--status-failed)' }}>
            <AlertTriangle size={28} strokeWidth={1.5} />
          </span>
          <div className="empty-state-title">{this.props.fallbackTitle ?? 'Something went wrong'}</div>
          <div className="empty-state-body mono" style={{ fontSize: 'var(--text-xs)' }}>
            {this.state.error.message}
          </div>
          <button type="button" className="btn btn-sm" onClick={this.reset}>
            Try again
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

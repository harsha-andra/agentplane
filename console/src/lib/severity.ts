import type { EventLevel } from '../types/api'

export function severityColorVar(level: EventLevel): string {
  switch (level) {
    case 'DEBUG':
      return 'var(--sev-debug)'
    case 'INFO':
      return 'var(--sev-info)'
    case 'WARN':
      return 'var(--sev-warn)'
    case 'ERROR':
      return 'var(--sev-error)'
    default:
      return 'var(--sev-info)'
  }
}

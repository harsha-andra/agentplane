import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatusBadge } from './StatusBadge'
import type { RunStatus } from '../types/api'

describe('StatusBadge', () => {
  it('renders a human-readable label and the raw status as a data attribute', () => {
    render(<StatusBadge status="TIMED_OUT" />)
    const label = screen.getByText('Timed Out')
    expect(label.closest('[data-status]')).toHaveAttribute('data-status', 'TIMED_OUT')
  })

  it('pulses the status dot only for RUNNING', () => {
    const { container, rerender } = render(<StatusBadge status="RUNNING" />)
    expect(container.querySelector('.badge-dot-pulse')).not.toBeNull()

    rerender(<StatusBadge status="SUCCEEDED" />)
    expect(container.querySelector('.badge-dot-pulse')).toBeNull()
  })

  it('renders every status without throwing', () => {
    const statuses: RunStatus[] = ['PENDING', 'SCHEDULED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT']
    for (const status of statuses) {
      const { unmount } = render(<StatusBadge status={status} />)
      unmount()
    }
  })
})

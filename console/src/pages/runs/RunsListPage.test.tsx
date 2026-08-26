import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { RunsListPage } from './RunsListPage'

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/runs']}>
        <Routes>
          <Route path="/runs" element={<RunsListPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RunsListPage', () => {
  it('renders the heading immediately and the seeded runs once demo data loads', async () => {
    renderPage()

    expect(screen.getByRole('heading', { name: 'Runs' })).toBeInTheDocument()

    // The mock layer resolves asynchronously (simulated network latency) —
    // wait for the real fleet size to show up in the subtitle.
    expect(await screen.findByText(/80 total/, undefined, { timeout: 3000 })).toBeInTheDocument()

    // And at least one run row with a real status badge should be showing.
    const table = screen.getByRole('table')
    expect(table.querySelectorAll('tbody tr').length).toBeGreaterThan(0)
  })
})

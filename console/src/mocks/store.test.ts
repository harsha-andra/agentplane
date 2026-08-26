import { describe, expect, it } from 'vitest'
import { listRuns, listTenants, listTraces } from './store'

describe('mock store: listRuns', () => {
  it('returns the full seeded fleet when unfiltered', () => {
    const page = listRuns({ page: 0, size: 200 })
    expect(page.totalElements).toBe(80)
    expect(page.content).toHaveLength(80)
  })

  it('paginates without overlap or gaps', () => {
    const first = listRuns({ page: 0, size: 10 })
    const second = listRuns({ page: 1, size: 10 })

    expect(first.content).toHaveLength(10)
    expect(second.content).toHaveLength(10)
    expect(first.number).toBe(0)
    expect(second.number).toBe(1)
    expect(first.totalPages).toBe(8)

    const firstIds = new Set(first.content.map((r) => r.id))
    for (const r of second.content) {
      expect(firstIds.has(r.id)).toBe(false)
    }
  })

  it('filters by status', () => {
    const all = listRuns({ page: 0, size: 200 })
    const expectedCount = all.content.filter((r) => r.status === 'SUCCEEDED').length

    const filtered = listRuns({ page: 0, size: 200, status: ['SUCCEEDED'] })

    expect(filtered.totalElements).toBe(expectedCount)
    expect(filtered.content.every((r) => r.status === 'SUCCEEDED')).toBe(true)
  })

  it('filters by multiple statuses (OR semantics)', () => {
    const filtered = listRuns({ page: 0, size: 200, status: ['FAILED', 'TIMED_OUT'] })
    expect(filtered.content.every((r) => r.status === 'FAILED' || r.status === 'TIMED_OUT')).toBe(true)
    expect(filtered.totalElements).toBeGreaterThan(0)
  })

  it('filters by tenantId', () => {
    const [tenant] = listTenants()
    const filtered = listRuns({ page: 0, size: 200, tenantId: tenant.id })

    expect(filtered.totalElements).toBeGreaterThan(0)
    expect(filtered.content.every((r) => r.tenantId === tenant.id)).toBe(true)
  })

  it('filters by free-text query across id/agent/tenant/job name', () => {
    const all = listRuns({ page: 0, size: 200 })
    const sample = all.content[0]

    const filtered = listRuns({ page: 0, size: 200, q: sample.agentName })

    expect(filtered.content.some((r) => r.id === sample.id)).toBe(true)
    expect(filtered.content.every((r) => r.agentName.toLowerCase().includes(sample.agentName.toLowerCase()))).toBe(true)
  })

  it('sorts newest-created first', () => {
    const all = listRuns({ page: 0, size: 200 })
    for (let i = 1; i < all.content.length; i++) {
      const prev = new Date(all.content[i - 1].createdAt).getTime()
      const curr = new Date(all.content[i].createdAt).getTime()
      expect(prev).toBeGreaterThanOrEqual(curr)
    }
  })
})

describe('mock store: listTraces', () => {
  it('filters by toolName', () => {
    const all = listTraces({ page: 0, size: 5000 })
    expect(all.totalElements).toBeGreaterThan(0)

    const toolName = all.content.find((t) => t.toolName)?.toolName
    expect(toolName).toBeTruthy()
    if (!toolName) return

    const filtered = listTraces({ toolName, page: 0, size: 5000 })
    expect(filtered.totalElements).toBeGreaterThan(0)
    expect(filtered.content.every((t) => t.toolName === toolName)).toBe(true)
  })

  it('filters by time window', () => {
    const all = listTraces({ page: 0, size: 5000 })
    const from = new Date(Date.now() - 60 * 60 * 1000).toISOString()

    const filtered = listTraces({ from, page: 0, size: 5000 })
    expect(filtered.content.every((t) => new Date(t.startedAt).getTime() >= new Date(from).getTime())).toBe(true)
    expect(filtered.totalElements).toBeLessThanOrEqual(all.totalElements)
  })

  it('paginates', () => {
    const page0 = listTraces({ page: 0, size: 25 })
    const page1 = listTraces({ page: 1, size: 25 })
    expect(page0.content).toHaveLength(25)
    const ids0 = new Set(page0.content.map((t) => t.id))
    for (const t of page1.content) expect(ids0.has(t.id)).toBe(false)
  })
})

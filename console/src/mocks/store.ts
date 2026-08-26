// In-memory "server" for demo mode. Owns the seeded dataset, applies live
// materialization (RUNNING → terminal, as real time passes), and answers
// every query the real control plane would (filtering, pagination,
// aggregation). api/*.ts calls into this instead of doing HTTP when
// DEMO_MODE is on.

import { AGENT_SEEDS } from './fixtures'
import { buildSeedData, createScriptedRun, type PendingCompletion, type ScriptedRun } from './seed'
import { intBetween, mulberry32 } from '../lib/random'
import type {
  CreateRunInput,
  CreateTenantInput,
  Overview,
  Page,
  RunDetail,
  RunEvent,
  RunListParams,
  RunStatus,
  RunSummary,
  Tenant,
  ToolLatency,
  Trace,
  TraceListParams,
  TraceRow,
} from '../types/api'

export class NotFoundError extends Error {}
export class ValidationError extends Error {}
export class ConflictError extends Error {}

const CANCELLABLE: RunStatus[] = ['PENDING', 'SCHEDULED', 'RUNNING']

const seedData = buildSeedData()
const tenantsById = new Map<string, Tenant>(seedData.tenants.map((t) => [t.id, t]))
const runsById = new Map<string, ScriptedRun>(seedData.scriptedRuns.map((sr) => [sr.run.id, sr]))
const runOrder: string[] = seedData.scriptedRuns.map((sr) => sr.run.id)

function applyCompletion(sr: ScriptedRun, completion: PendingCompletion) {
  const startedMs = sr.run.startedAt ? new Date(sr.run.startedAt).getTime() : completion.atMs
  sr.run.status = completion.status
  sr.run.finishedAt = new Date(completion.atMs).toISOString()
  sr.run.durationMs = completion.atMs - startedMs
  sr.run.podPhase = completion.podPhase
  sr.run.exitCode = completion.exitCode
  sr.run.message = completion.message
  sr.pendingCompletion = null
}

function materialize(sr: ScriptedRun, atMs: number): void {
  if (sr.pendingCompletion && sr.pendingCompletion.atMs <= atMs) {
    applyCompletion(sr, sr.pendingCompletion)
  }
}

function allScripted(atMs: number): ScriptedRun[] {
  const list = runOrder.map((id) => runsById.get(id)!)
  for (const sr of list) materialize(sr, atMs)
  return list
}

function toSummary(run: RunDetail): RunSummary {
  const {
    id,
    tenantId,
    tenantName,
    agentName,
    status,
    createdAt,
    startedAt,
    finishedAt,
    durationMs,
    k8sJobName,
    namespace,
    attempt,
    idempotencyKey,
  } = run
  return { id, tenantId, tenantName, agentName, status, createdAt, startedAt, finishedAt, durationMs, k8sJobName, namespace, attempt, idempotencyKey }
}

export function listRuns(params: RunListParams): Page<RunSummary> {
  const now = Date.now()
  let all = allScripted(now).map((sr) => sr.run)

  if (params.status && params.status.length > 0) {
    const set = new Set(params.status)
    all = all.filter((r) => set.has(r.status))
  }
  if (params.tenantId) {
    all = all.filter((r) => r.tenantId === params.tenantId)
  }
  if (params.q && params.q.trim()) {
    const q = params.q.trim().toLowerCase()
    all = all.filter(
      (r) =>
        r.id.toLowerCase().includes(q) ||
        r.agentName.toLowerCase().includes(q) ||
        r.tenantName.toLowerCase().includes(q) ||
        r.k8sJobName.toLowerCase().includes(q),
    )
  }

  all = [...all].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())

  const size = params.size && params.size > 0 ? params.size : 20
  const page = params.page && params.page > 0 ? params.page : 0
  const totalElements = all.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const start = page * size
  const content = all.slice(start, start + size).map(toSummary)

  return { content, totalElements, totalPages, number: page, size }
}

export function getRun(id: string): RunDetail {
  const sr = runsById.get(id)
  if (!sr) throw new NotFoundError(`Run ${id} not found`)
  materialize(sr, Date.now())
  return { ...sr.run }
}

export function getVisibleEvents(runId: string, atMs: number = Date.now()): RunEvent[] {
  const sr = runsById.get(runId)
  if (!sr) throw new NotFoundError(`Run ${runId} not found`)
  materialize(sr, atMs)
  return sr.timeline.filter((e) => new Date(e.ts).getTime() <= atMs)
}

export function getTraces(runId: string): Trace[] {
  const sr = runsById.get(runId)
  if (!sr) throw new NotFoundError(`Run ${runId} not found`)
  return sr.traces
}

export function cancelRun(id: string): RunDetail {
  const sr = runsById.get(id)
  if (!sr) throw new NotFoundError(`Run ${id} not found`)
  const nowMs = Date.now()
  materialize(sr, nowMs)
  if (!CANCELLABLE.includes(sr.run.status)) {
    throw new ConflictError(`Run ${id} is ${sr.run.status} and can no longer be cancelled`)
  }
  const startedMs = sr.run.startedAt ? new Date(sr.run.startedAt).getTime() : nowMs
  sr.run.status = 'CANCELLED'
  sr.run.finishedAt = new Date(nowMs).toISOString()
  sr.run.durationMs = nowMs - startedMs
  sr.run.podPhase = sr.run.startedAt ? 'Failed' : null
  sr.run.exitCode = sr.run.startedAt ? 137 : null
  sr.run.message = 'Cancelled by operator'
  sr.pendingCompletion = null

  sr.timeline = sr.timeline.filter((e) => new Date(e.ts).getTime() <= nowMs)
  const lastEvent = sr.timeline[sr.timeline.length - 1]
  const nextSeq = (lastEvent?.seq ?? 0) + 1
  sr.timeline.push({
    seq: nextSeq,
    runId: id,
    ts: new Date(nowMs).toISOString(),
    level: 'WARN',
    source: 'k8s-controller',
    message: 'Run cancelled by operator; pod terminated (SIGTERM)',
    phase: 'CANCELLED',
  })
  return { ...sr.run }
}

function resolveAgent(name: string) {
  return AGENT_SEEDS.find((a) => a.name === name) ?? AGENT_SEEDS[0]
}

export function createRun(input: CreateRunInput): RunDetail {
  const tenant = tenantsById.get(input.tenantId)
  if (!tenant) throw new ValidationError(`Unknown tenant ${input.tenantId}`)
  if (!input.spec.agentName.trim()) throw new ValidationError('agentName is required')
  if (!input.spec.prompt.trim()) throw new ValidationError('prompt is required')
  const agent = resolveAgent(input.spec.agentName)
  const sr = createScriptedRun({ tenant, agent, spec: input.spec, attempt: 1 })
  runsById.set(sr.run.id, sr)
  runOrder.unshift(sr.run.id)
  return { ...sr.run }
}

export function retryRun(id: string): RunDetail {
  const sr = runsById.get(id)
  if (!sr) throw new NotFoundError(`Run ${id} not found`)
  const tenant = tenantsById.get(sr.run.tenantId)
  if (!tenant) throw new NotFoundError(`Tenant ${sr.run.tenantId} not found`)
  const agent = resolveAgent(sr.run.agentName)
  const next = createScriptedRun({ tenant, agent, spec: sr.run.spec, attempt: sr.run.attempt + 1 })
  runsById.set(next.run.id, next)
  runOrder.unshift(next.run.id)
  return { ...next.run }
}

export function listTenants(): Tenant[] {
  const now = Date.now()
  const runs = allScripted(now).map((sr) => sr.run)
  return Array.from(tenantsById.values()).map((t) => ({
    ...t,
    activeRuns: runs.filter((r) => r.tenantId === t.id && r.status === 'RUNNING').length,
  }))
}

const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/

export function createTenant(input: CreateTenantInput): Tenant {
  const name = input.name.trim()
  const slug = input.slug.trim().toLowerCase()
  const namespace = input.namespace.trim()
  if (!name) throw new ValidationError('Name is required')
  if (!slug || !SLUG_PATTERN.test(slug)) {
    throw new ValidationError('Slug must be lowercase alphanumeric, hyphen-separated (e.g. "acme-labs")')
  }
  if (!namespace) throw new ValidationError('Namespace is required')
  if (!input.quotaCpu.trim()) throw new ValidationError('CPU quota is required')
  if (!input.quotaMemory.trim()) throw new ValidationError('Memory quota is required')
  if (!Number.isFinite(input.maxConcurrentRuns) || input.maxConcurrentRuns < 1) {
    throw new ValidationError('Max concurrent runs must be at least 1')
  }
  const id = `tenant_${slug}`
  if (tenantsById.has(id)) throw new ConflictError(`A tenant with slug "${slug}" already exists`)

  const tenant: Tenant = {
    id,
    name,
    slug,
    namespace,
    quotaCpu: input.quotaCpu.trim(),
    quotaMemory: input.quotaMemory.trim(),
    maxConcurrentRuns: Math.round(input.maxConcurrentRuns),
    activeRuns: 0,
    createdAt: new Date().toISOString(),
  }
  tenantsById.set(id, tenant)
  return tenant
}

export function getToolLatency(days = 7): ToolLatency[] {
  const cutoff = Date.now() - days * 24 * 60 * 60 * 1000
  const buckets = new Map<string, { count: number; sum: number; latencies: number[]; errors: number }>()
  for (const id of runOrder) {
    const sr = runsById.get(id)!
    for (const t of sr.traces) {
      if (t.type !== 'TOOL_CALL' || !t.toolName) continue
      if (new Date(t.startedAt).getTime() < cutoff) continue
      const bucket = buckets.get(t.toolName) ?? { count: 0, sum: 0, latencies: [], errors: 0 }
      bucket.count += 1
      bucket.sum += t.latencyMs
      bucket.latencies.push(t.latencyMs)
      if (t.status === 'ERROR') bucket.errors += 1
      buckets.set(t.toolName, bucket)
    }
  }
  const result: ToolLatency[] = []
  for (const [toolName, b] of buckets) {
    const sorted = [...b.latencies].sort((x, y) => x - y)
    const p95Index = Math.min(sorted.length - 1, Math.floor(0.95 * sorted.length))
    result.push({
      toolName,
      callCount: b.count,
      avgLatencyMs: Math.round(b.sum / b.count),
      p95LatencyMs: sorted[p95Index] ?? 0,
      errorRate: b.count > 0 ? b.errors / b.count : 0,
    })
  }
  return result.sort((a, b) => b.callCount - a.callCount)
}

/** Cross-run trace explorer backing /traces — see CreateRunInput/TraceRow
 * doc comment in types/api.ts for why this exists beyond the given
 * per-run contract endpoint. */
export function listTraces(params: TraceListParams): Page<TraceRow> {
  const now = Date.now()
  const rows: TraceRow[] = []
  for (const id of runOrder) {
    const sr = runsById.get(id)!
    materialize(sr, now)
    for (const t of sr.traces) {
      rows.push({ ...t, tenantName: sr.run.tenantName, agentName: sr.run.agentName })
    }
  }
  let filtered = rows
  if (params.toolName) filtered = filtered.filter((r) => r.toolName === params.toolName)
  if (params.type) filtered = filtered.filter((r) => r.type === params.type)
  if (params.from) {
    const from = new Date(params.from).getTime()
    filtered = filtered.filter((r) => new Date(r.startedAt).getTime() >= from)
  }
  if (params.to) {
    const to = new Date(params.to).getTime()
    filtered = filtered.filter((r) => new Date(r.startedAt).getTime() <= to)
  }
  if (params.q && params.q.trim()) {
    const q = params.q.trim().toLowerCase()
    filtered = filtered.filter(
      (r) =>
        (r.toolName ?? '').toLowerCase().includes(q) ||
        r.runId.toLowerCase().includes(q) ||
        r.agentName.toLowerCase().includes(q) ||
        r.tenantName.toLowerCase().includes(q),
    )
  }
  filtered = [...filtered].sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())

  const size = params.size && params.size > 0 ? params.size : 25
  const page = params.page && params.page > 0 ? params.page : 0
  const totalElements = filtered.length
  const totalPages = Math.max(1, Math.ceil(totalElements / size))
  const start = page * size
  return { content: filtered.slice(start, start + size), totalElements, totalPages, number: page, size }
}

export function getOverview(): Overview {
  const now = Date.now()
  const runs = allScripted(now).map((sr) => sr.run)

  const activeRuns = runs.filter((r) => r.status === 'RUNNING').length
  const queuedRuns = runs.filter((r) => r.status === 'PENDING' || r.status === 'SCHEDULED').length

  // streamDepth is a stylized "events buffered in the ingestion stream"
  // metric — plausible and gently moving, but stable within a short tick
  // window so it doesn't jitter on every refetch.
  const tickRng = mulberry32(Math.floor(now / 4000) ^ 0x5bd1e995)
  const streamDepth = activeRuns * intBetween(tickRng, 1, 4) + intBetween(tickRng, 0, 6)

  const dayMs = 24 * 60 * 60 * 1000
  const finishedLast24h = runs.filter((r) => r.finishedAt && new Date(r.finishedAt).getTime() >= now - dayMs)
  const succeeded24h = finishedLast24h.filter((r) => r.status === 'SUCCEEDED').length
  const successRate24h = finishedLast24h.length > 0 ? succeeded24h / finishedLast24h.length : 1

  const durations = finishedLast24h
    .map((r) => r.durationMs)
    .filter((d): d is number => d !== null)
    .sort((a, b) => a - b)
  const p95DurationMs = durations.length > 0 ? durations[Math.min(durations.length - 1, Math.floor(0.95 * durations.length))] : 0

  const tokenSpend24h = runs
    .filter((r) => new Date(r.createdAt).getTime() >= now - dayMs)
    .reduce((sum, r) => sum + r.costUsd, 0)

  const runsOverTime: Overview['runsOverTime'] = []
  for (let i = 23; i >= 0; i--) {
    const bucketStart = now - (i + 1) * 60 * 60 * 1000
    const bucketEnd = now - i * 60 * 60 * 1000
    const inBucket = runs.filter((r) => {
      if (!r.finishedAt) return false
      const t = new Date(r.finishedAt).getTime()
      return t >= bucketStart && t < bucketEnd
    })
    runsOverTime.push({
      ts: new Date(bucketEnd).toISOString(),
      succeeded: inBucket.filter((r) => r.status === 'SUCCEEDED').length,
      failed: inBucket.filter((r) => r.status === 'FAILED' || r.status === 'TIMED_OUT').length,
      cancelled: inBucket.filter((r) => r.status === 'CANCELLED').length,
    })
  }

  const toolLatency = getToolLatency(7).slice(0, 8)
  const tenantUtilization = listTenants().map((t) => ({
    tenantName: t.name,
    activeRuns: t.activeRuns,
    maxConcurrentRuns: t.maxConcurrentRuns,
  }))

  const recentEvents: RunEvent[] = []
  for (const id of runOrder) {
    const sr = runsById.get(id)!
    for (const e of sr.timeline) {
      if (new Date(e.ts).getTime() <= now) recentEvents.push(e)
    }
  }
  recentEvents.sort((a, b) => new Date(b.ts).getTime() - new Date(a.ts).getTime())

  return {
    activeRuns,
    queuedRuns,
    streamDepth,
    successRate24h,
    p95DurationMs,
    tokenSpend24h,
    runsOverTime,
    toolLatency,
    tenantUtilization,
    recentEvents: recentEvents.slice(0, 25),
  }
}

// API contract types — mirrors the Spring Boot control plane exactly.
// Base path: /api/v1

export type RunStatus =
  | 'PENDING'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'

export interface RunSpec {
  agentName: string
  image: string
  prompt: string
  model: string
  maxSteps: number
  timeoutSeconds: number
  env: Record<string, string>
  resources: { cpu: string; memory: string }
}

export interface RunSummary {
  id: string
  tenantId: string
  tenantName: string
  agentName: string
  status: RunStatus
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  durationMs: number | null
  k8sJobName: string
  namespace: string
  attempt: number
  idempotencyKey: string
}

export interface RunDetail extends RunSummary {
  spec: RunSpec
  podPhase: string | null
  exitCode: number | null
  restartCount: number
  nodeName: string | null
  message: string | null
  stepCount: number
  tokenUsage: { prompt: number; completion: number; total: number }
  costUsd: number
}

export type EventLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

export interface RunEvent {
  seq: number
  runId: string
  ts: string
  level: EventLevel
  source: string
  message: string
  phase?: string
}

export type TraceType = 'LLM_CALL' | 'TOOL_CALL' | 'DECISION' | 'ERROR'

export interface Trace {
  id: string
  runId: string
  seq: number
  type: TraceType
  toolName: string | null
  startedAt: string
  latencyMs: number
  status: 'OK' | 'ERROR'
  payload: Record<string, unknown>
  error: string | null
}

export interface Tenant {
  id: string
  name: string
  slug: string
  namespace: string
  quotaCpu: string
  quotaMemory: string
  maxConcurrentRuns: number
  activeRuns: number
  createdAt: string
}

export interface ToolLatency {
  toolName: string
  callCount: number
  avgLatencyMs: number
  p95LatencyMs: number
  errorRate: number
}

export interface Overview {
  activeRuns: number
  queuedRuns: number
  streamDepth: number
  successRate24h: number
  p95DurationMs: number
  tokenSpend24h: number
  runsOverTime: { ts: string; succeeded: number; failed: number; cancelled: number }[]
  toolLatency: ToolLatency[]
  tenantUtilization: { tenantName: string; activeRuns: number; maxConcurrentRuns: number }[]
  recentEvents: RunEvent[]
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface RunListParams {
  page?: number
  size?: number
  status?: RunStatus[]
  tenantId?: string
  q?: string
}

/** Request body for POST /runs. */
export interface CreateRunInput {
  tenantId: string
  spec: RunSpec
}

/** Request body for POST /tenants. */
export type CreateTenantInput = Pick<Tenant, 'name' | 'slug' | 'namespace' | 'quotaCpu' | 'quotaMemory' | 'maxConcurrentRuns'>

/**
 * Extends the given contract with a cross-run trace explorer, needed to
 * power the /traces page (the documented contract only exposes traces
 * scoped to a single run via GET /runs/{id}/traces). See console/README.md
 * "API contract notes".
 */
export interface TraceRow extends Trace {
  tenantName: string
  agentName: string
}

export interface TraceListParams {
  toolName?: string
  type?: TraceType
  from?: string
  to?: string
  q?: string
  page?: number
  size?: number
}

// Generates the seeded demo dataset: ~80 runs across 5 tenants, ~500 traces,
// and a full per-run event timeline (used for both instant replay of
// finished runs and the simulated live tail of still-running ones).
//
// Design: every run gets a *complete* chronological timeline computed once,
// at seed time — including, for runs that are still RUNNING, events whose
// timestamp lies in the future. Nothing needs a background timer to "write"
// new events; the store simply reveals events whose `ts` has now arrived
// (see mocks/store.ts). This keeps behavior deterministic, consistent
// across multiple tabs/reconnects, and cheap.

import type {
  RunDetail,
  RunEvent,
  RunSpec,
  RunStatus,
  Tenant,
  Trace,
  TraceType,
} from '../types/api'
import { AGENT_SEEDS, MODEL_RATES, TENANT_SEEDS, TOOL_PROFILES, type AgentSeed } from './fixtures'
import { chance, floatBetween, intBetween, mulberry32, pick, shortId, weightedPick, type Rng } from '../lib/random'

const SEED = 0xa6e17

export interface ScriptedRun {
  run: RunDetail
  traces: Trace[]
  /** Full chronological event history. For a run still RUNNING at seed
   * time, later entries carry a `ts` in the future — see store.ts. */
  timeline: RunEvent[]
  /** Set only for runs that were RUNNING at seed time: what to apply to the
   * run once real time reaches `atMs`. */
  pendingCompletion: PendingCompletion | null
}

export interface PendingCompletion {
  atMs: number
  status: RunStatus
  podPhase: string
  exitCode: number | null
  message: string | null
}

const now = Date.now()

function makeTenants(rng: Rng): Tenant[] {
  return TENANT_SEEDS.map((seed, i) => ({
    id: `tenant_${seed.slug}`,
    name: seed.name,
    slug: seed.slug,
    namespace: seed.namespace,
    quotaCpu: seed.quotaCpu,
    quotaMemory: seed.quotaMemory,
    maxConcurrentRuns: seed.maxConcurrentRuns,
    activeRuns: 0, // filled in after runs are generated
    createdAt: new Date(now - (400 + i * 37) * 24 * 60 * 60 * 1000 - intBetween(rng, 0, 1e6)).toISOString(),
  }))
}

const STATUS_WEIGHTS: readonly (readonly [RunStatus, number])[] = [
  ['SUCCEEDED', 38],
  ['FAILED', 10],
  ['RUNNING', 8],
  ['PENDING', 6],
  ['SCHEDULED', 4],
  ['CANCELLED', 8],
  ['TIMED_OUT', 6],
]

const RUN_COUNT = 80

function rateFor(model: string) {
  return MODEL_RATES.find((m) => m.model === model) ?? MODEL_RATES[1]
}

function toolArgs(rng: Rng, tool: string): Record<string, unknown> {
  switch (tool) {
    case 'web_search':
      return { query: pick(rng, ['competitor pricing 2026', 'zero downtime postgres migration', 'SOC2 CC6.1 controls', 'LLM inference cost benchmarks']), topK: 5 }
    case 'http_request':
      return { method: pick(rng, ['GET', 'POST']), url: `https://api.internal.example.com/v1/${pick(rng, ['accounts', 'tickets', 'invoices', 'events'])}/${shortId(rng, 6)}` }
    case 'sql_query':
      return { query: pick(rng, [
        'select tenant_id, count(*) from runs where status = $1 group by 1',
        'select sum(amount) from invoices where due_date < now()',
        "select * from billing_events where created_at > now() - interval '7 days'",
      ]) }
    case 'vector_search':
      return { query: pick(rng, ['renewal clause', 'data retention policy', 'refund eligibility']), namespace: pick(rng, ['kb-support', 'kb-legal', 'kb-eng']), topK: 8 }
    case 'code_exec':
      return { language: 'python', entrypoint: pick(rng, ['main.py', 'analyze.py', 'run_tests.py']) }
    case 'file_read':
      return { path: `/workspace/${pick(rng, ['input', 'data', 'docs'])}/${shortId(rng, 6)}.json` }
    case 'file_write':
      return { path: `/workspace/output/${shortId(rng, 6)}.md` }
    case 'send_email':
      return { to: `${pick(rng, ['ops', 'billing', 'support'])}@customer-${shortId(rng, 4)}.example.com`, subject: pick(rng, ['Re: your recent ticket', 'Invoice discrepancy follow-up', 'Incident update']) }
    case 'slack_post':
      return { channel: pick(rng, ['#incidents', '#support-escalations', '#on-call']) }
    case 'k8s_exec':
      return { pod: `agent-run-${shortId(rng, 8)}`, command: pick(rng, ['ps aux', 'df -h', 'cat /proc/1/status']) }
    case 'pdf_extract':
      return { documentId: `doc_${shortId(rng, 10)}` }
    case 'calendar_lookup':
      return { attendee: `${pick(rng, ['jsmith', 'agarcia', 'rpatel'])}@example.com`, range: '7d' }
    default:
      return {}
  }
}

function toolResultPreview(rng: Rng, tool: string, ok: boolean): string {
  if (!ok) {
    return pick(rng, [
      'connection refused',
      'timed out after 30000ms',
      'HTTP 503 from upstream',
      'rate limited (429)',
      'unexpected EOF reading response body',
    ])
  }
  switch (tool) {
    case 'web_search':
      return `${intBetween(rng, 3, 9)} results retrieved`
    case 'sql_query':
      return `${intBetween(rng, 1, 4200)} rows returned`
    case 'vector_search':
      return `${intBetween(rng, 1, 8)} matches above threshold 0.78`
    case 'code_exec':
      return `exit 0, ${intBetween(rng, 1, 40)} test(s) passed`
    case 'send_email':
      return 'queued for delivery'
    case 'slack_post':
      return 'message posted'
    case 'k8s_exec':
      return 'command completed'
    case 'pdf_extract':
      return `${intBetween(rng, 1, 40)} pages extracted`
    default:
      return 'ok'
  }
}

/** Builds the ordered trace list for one run and returns it alongside
 * derived token/cost totals. */
function buildTraces(rng: Rng, runId: string, agentTools: string[], model: string, startedAtMs: number, stepBudgetMs: number, hasErrorStep: boolean): { traces: Trace[]; promptTokens: number; completionTokens: number } {
  const stepCount = intBetween(rng, 3, 12)
  const traces: Trace[] = []
  let cursor = startedAtMs
  let promptTokens = 0
  let completionTokens = 0
  const errorStepIndex = hasErrorStep ? intBetween(rng, Math.floor(stepCount / 2), stepCount - 1) : -1

  for (let i = 0; i < stepCount; i++) {
    const type: TraceType = weightedPick(rng, [
      ['TOOL_CALL', 55],
      ['LLM_CALL', 30],
      ['DECISION', 15],
    ])
    const isErrorStep = i === errorStepIndex
    let latencyMs: number
    let toolName: string | null = null
    let payload: Record<string, unknown>
    let status: 'OK' | 'ERROR' = isErrorStep ? 'ERROR' : 'OK'
    let error: string | null = null

    if (type === 'TOOL_CALL') {
      toolName = pick(rng, agentTools)
      const profile = TOOL_PROFILES.find((t) => t.name === toolName)
      latencyMs = Math.round((profile?.baseLatencyMs ?? 100) + floatBetween(rng, 0, profile?.jitterMs ?? 200))
      const ok = !isErrorStep
      const preview = toolResultPreview(rng, toolName, ok)
      payload = { args: toolArgs(rng, toolName), result: ok ? preview : undefined }
      if (!ok) {
        error = `${toolName} failed: ${preview}`
        payload = { args: payload.args, error: preview }
      }
    } else if (type === 'LLM_CALL') {
      const pTok = intBetween(rng, 180, 1400)
      const cTok = intBetween(rng, 60, 900)
      promptTokens += pTok
      completionTokens += cTok
      latencyMs = intBetween(rng, 400, 5200)
      payload = { model, promptTokens: pTok, completionTokens: cTok, temperature: 0.2, stopReason: isErrorStep ? 'error' : 'end_turn' }
      if (isErrorStep) error = 'model request failed: upstream 503'
    } else {
      latencyMs = intBetween(rng, 8, 160)
      payload = {
        thought: pick(rng, [
          'Enough context gathered — proceeding to synthesize a final answer.',
          'Ambiguous result; issuing a follow-up tool call to disambiguate.',
          'Sufficient evidence found; drafting the response now.',
          'Result set too large; narrowing the query.',
        ]),
        nextAction: pick(rng, ['tool_call', 'respond', 'replan']),
      }
    }

    traces.push({
      id: `trace_${shortId(rng, 12)}`,
      runId,
      seq: i + 1,
      type,
      toolName,
      startedAt: new Date(cursor).toISOString(),
      latencyMs,
      status,
      payload,
      error,
    })
    cursor += latencyMs + intBetween(rng, 20, 200)
  }

  // Squeeze the generated cursor into the allotted step budget so timelines
  // stay proportionate regardless of how many steps were rolled.
  const span = cursor - startedAtMs
  if (span > stepBudgetMs && span > 0) {
    const scale = stepBudgetMs / span
    let acc = startedAtMs
    for (const t of traces) {
      const rel = (new Date(t.startedAt).getTime() - startedAtMs) * scale
      t.startedAt = new Date(startedAtMs + rel).toISOString()
      acc = startedAtMs + rel
    }
    void acc
  }

  return { traces, promptTokens, completionTokens }
}

function buildTimeline(
  rng: Rng,
  run: RunDetail,
  traces: Trace[],
  endMs: number | null,
  outcome: { status: RunStatus; message: string | null } | null,
  injectCertError: boolean,
): RunEvent[] {
  const events: RunEvent[] = []
  let seq = 1
  const push = (tsMs: number, level: RunEvent['level'], source: string, message: string, phase?: string) => {
    events.push({ seq: seq++, runId: run.id, ts: new Date(tsMs).toISOString(), level, source, message, phase })
  }

  const createdMs = new Date(run.createdAt).getTime()
  push(createdMs, 'INFO', 'scheduler', `Run accepted (idempotency key ${run.idempotencyKey}); enqueued for scheduling`, 'ACCEPTED')

  if (run.status === 'PENDING') return events

  const scheduledMs = createdMs + intBetween(rng, 400, 4000)
  push(scheduledMs, 'INFO', 'scheduler', `Bound to namespace ${run.namespace}; requesting job ${run.k8sJobName}`, 'SCHEDULING')

  if (run.status === 'SCHEDULED') return events

  const startedMs = run.startedAt ? new Date(run.startedAt).getTime() : scheduledMs + 500
  push(startedMs - intBetween(rng, 200, 900), 'INFO', 'k8s-controller', `Pod ${run.k8sJobName}-0 scheduled to node ${run.nodeName ?? 'unassigned'}`, 'SCHEDULING')
  push(startedMs, 'INFO', 'k8s-controller', `Pod ${run.k8sJobName}-0 entered Running phase`, 'RUNNING')
  push(startedMs + intBetween(rng, 40, 250), 'INFO', 'agent-runtime', `Runtime initialized from ${run.spec.image}`, 'RUNNING')
  push(startedMs + intBetween(rng, 260, 500), 'DEBUG', 'agent-runtime', `Loaded spec: model=${run.spec.model} maxSteps=${run.spec.maxSteps} timeout=${run.spec.timeoutSeconds}s`, 'RUNNING')

  const stepWindowEnd = endMs ?? startedMs + 60_000
  for (const t of traces) {
    const stepStart = new Date(t.startedAt).getTime()
    const clamped = Math.min(stepStart, Math.max(startedMs, stepWindowEnd - 200))
    if (t.type === 'LLM_CALL') {
      push(clamped, 'DEBUG', 'llm-gateway', `Calling ${(t.payload.model as string) ?? run.spec.model} (~${t.payload.promptTokens} prompt tokens)`, 'RUNNING')
      const lvl = t.status === 'ERROR' ? 'ERROR' : 'INFO'
      const msg = t.status === 'ERROR'
        ? `Model call failed: ${t.error}`
        : `Model responded in ${t.latencyMs}ms (${t.payload.completionTokens} completion tokens)`
      push(clamped + t.latencyMs, lvl, 'llm-gateway', msg, 'RUNNING')
    } else if (t.type === 'TOOL_CALL') {
      push(clamped, 'DEBUG', 'tool-runner', `Invoking tool ${t.toolName}`, 'RUNNING')
      if (t.status === 'ERROR') {
        push(clamped + t.latencyMs, 'ERROR', 'tool-runner', `${t.toolName} failed: ${t.error}`, 'RUNNING')
        if (chance(rng, 0.7)) {
          push(clamped + t.latencyMs + 120, 'WARN', 'agent-runtime', `Retrying step after tool error (attempt 2/3)`, 'RUNNING')
        }
      } else {
        push(clamped + t.latencyMs, 'INFO', 'tool-runner', `${t.toolName} completed in ${t.latencyMs}ms`, 'RUNNING')
      }
    } else {
      push(clamped, 'DEBUG', 'agent-runtime', String(t.payload.thought), 'RUNNING')
    }
  }

  if (injectCertError) {
    const certMs = startedMs + intBetween(rng, 500, Math.max(600, stepWindowEnd - startedMs - 500))
    push(certMs, 'ERROR', 'tool-runner', 'TLS handshake failed: x509: certificate signed by unknown authority (tool=http_request)', 'RUNNING')
    push(certMs + intBetween(rng, 150, 400), 'WARN', 'tool-runner', 'Retrying with system trust store after certificate verification failure', 'RUNNING')
    push(certMs + intBetween(rng, 450, 900), 'INFO', 'tool-runner', 'TLS handshake succeeded after retry', 'RUNNING')
  }

  if (outcome && endMs) {
    if (outcome.status === 'SUCCEEDED') {
      push(endMs, 'INFO', 'agent-runtime', `Run completed successfully after ${traces.length} step(s)`, 'COMPLETED')
    } else if (outcome.status === 'FAILED') {
      push(endMs, 'ERROR', 'agent-runtime', outcome.message ?? 'Run failed', 'FAILED')
    } else if (outcome.status === 'TIMED_OUT') {
      push(endMs, 'ERROR', 'k8s-controller', `Run exceeded configured timeout of ${run.spec.timeoutSeconds}s; pod terminated`, 'TIMED_OUT')
    } else if (outcome.status === 'CANCELLED') {
      push(endMs, 'WARN', 'k8s-controller', 'Run cancelled by operator; pod terminated (SIGTERM)', 'CANCELLED')
    }
  }

  return events
}

function buildOneRun(rng: Rng, index: number, tenants: Tenant[]): ScriptedRun {
  const tenant = pick(rng, tenants)
  const agent = pick(rng, AGENT_SEEDS)
  const model = pick(rng, agent.models)
  const rate = rateFor(model)
  const status = weightedPick(rng, STATUS_WEIGHTS)
  const id = `run_${shortId(rng, 14)}`
  const attempt = chance(rng, 0.18) ? intBetween(rng, 2, 3) : 1
  const idempotencyKey = `idem_${shortId(rng, 20)}`
  const k8sJobName = `agent-run-${id.slice(4, 12)}`

  // Recency-weighted creation time across the last ~4 days, RUNNING/PENDING/
  // SCHEDULED biased to be very recent so the operational view feels live.
  const isActive = status === 'RUNNING' || status === 'PENDING' || status === 'SCHEDULED'
  const ageMs = isActive
    ? intBetween(rng, 2_000, 20 * 60_000)
    : intBetween(rng, 2 * 60_000, 4 * 24 * 60 * 60 * 1000)
  const createdAtMs = now - ageMs

  const spec: RunSpec = {
    agentName: agent.name,
    image: agent.image,
    prompt: pick(rng, agent.promptSamples),
    model,
    maxSteps: intBetween(rng, 6, 20),
    timeoutSeconds: pick(rng, [60, 90, 120, 180, 300]),
    env: { AGENT_ENV: pick(rng, ['production', 'staging']), LOG_LEVEL: pick(rng, ['info', 'debug']) },
    resources: { cpu: pick(rng, ['250m', '500m', '1', '2']), memory: pick(rng, ['512Mi', '1Gi', '2Gi', '4Gi']) },
  }

  let startedAt: string | null = null
  let finishedAt: string | null = null
  let durationMs: number | null = null
  let podPhase: string | null = null
  let exitCode: number | null = null
  let restartCount = 0
  let message: string | null = null
  const nodeName = status === 'PENDING' ? null : `gke-agentplane-pool-${pick(rng, ['a', 'b', 'c'])}-${shortId(rng, 6)}`

  let pendingCompletion: PendingCompletion | null = null
  let endMs: number | null = null
  let outcomeForTimeline: { status: RunStatus; message: string | null } | null = null

  if (status === 'PENDING') {
    podPhase = null
  } else if (status === 'SCHEDULED') {
    podPhase = 'Pending'
  } else if (status === 'RUNNING') {
    const startedAtMs = createdAtMs + intBetween(rng, 500, 4000)
    startedAt = new Date(startedAtMs).toISOString()
    podPhase = 'Running'
    // This run is still in flight from the viewer's perspective: pick a
    // future completion time and outcome; store.ts materializes it once
    // real time reaches `atMs`.
    const completionInMs = intBetween(rng, 25_000, 210_000)
    const finalStatus = weightedPick<RunStatus>(rng, [
      ['SUCCEEDED', 70],
      ['FAILED', 18],
      ['TIMED_OUT', 12],
    ])
    const finalMessage =
      finalStatus === 'FAILED'
        ? pick(rng, [
            `Agent exceeded max steps (${spec.maxSteps}) without a final answer`,
            'Unrecoverable tool error after 3 retries',
            'Model provider returned a non-retryable error',
          ])
        : finalStatus === 'TIMED_OUT'
          ? `Run exceeded configured timeout of ${spec.timeoutSeconds}s`
          : null
    pendingCompletion = {
      atMs: now + completionInMs,
      status: finalStatus,
      podPhase: finalStatus === 'SUCCEEDED' ? 'Succeeded' : 'Failed',
      exitCode: finalStatus === 'SUCCEEDED' ? 0 : finalStatus === 'TIMED_OUT' ? 124 : intBetween(rng, 1, 3),
      message: finalMessage,
    }
    endMs = pendingCompletion.atMs
    outcomeForTimeline = { status: finalStatus, message: finalMessage }
  } else {
    // Terminal at seed time.
    const startedAtMs = createdAtMs + intBetween(rng, 500, 4000)
    startedAt = new Date(startedAtMs).toISOString()
    const durMs = intBetween(rng, 3_000, 220_000)
    const finMs = startedAtMs + durMs
    finishedAt = new Date(finMs).toISOString()
    durationMs = durMs
    endMs = finMs
    exitCode = status === 'SUCCEEDED' ? 0 : status === 'TIMED_OUT' ? 124 : status === 'CANCELLED' ? 137 : intBetween(rng, 1, 3)
    podPhase = status === 'SUCCEEDED' ? 'Succeeded' : 'Failed'
    restartCount = chance(rng, 0.12) ? intBetween(rng, 1, 2) : 0
    message =
      status === 'FAILED'
        ? pick(rng, [
            `Agent exceeded max steps (${spec.maxSteps}) without a final answer`,
            'Unrecoverable tool error after 3 retries',
            'Model provider returned a non-retryable error',
          ])
        : status === 'TIMED_OUT'
          ? `Run exceeded configured timeout of ${spec.timeoutSeconds}s`
          : status === 'CANCELLED'
            ? 'Cancelled by operator'
            : null
    outcomeForTimeline = { status, message }
  }

  const startedAtMsResolved = startedAt ? new Date(startedAt).getTime() : createdAtMs
  const stepBudgetMs = endMs ? Math.max(1000, endMs - startedAtMsResolved - 400) : 60_000
  const hasErrorStep = status === 'FAILED' || (status === 'RUNNING' && pendingCompletion?.status === 'FAILED')

  const { traces, promptTokens, completionTokens } =
    startedAt !== null
      ? buildTraces(rng, id, agent.tools, model, startedAtMsResolved + 600, stepBudgetMs, hasErrorStep)
      : { traces: [] as Trace[], promptTokens: 0, completionTokens: 0 }

  const totalTokens = promptTokens + completionTokens
  const costUsd = (promptTokens / 1000) * rate.inPer1k + (completionTokens / 1000) * rate.outPer1k

  const run: RunDetail = {
    id,
    tenantId: tenant.id,
    tenantName: tenant.name,
    agentName: agent.name,
    status,
    createdAt: new Date(createdAtMs).toISOString(),
    startedAt,
    finishedAt,
    durationMs,
    k8sJobName,
    namespace: tenant.namespace,
    attempt,
    idempotencyKey,
    spec,
    podPhase,
    exitCode,
    restartCount,
    nodeName,
    message,
    stepCount: traces.length,
    tokenUsage: { prompt: promptTokens, completion: completionTokens, total: totalTokens },
    costUsd: Math.round(costUsd * 10000) / 10000,
  }

  const injectCertError = index % 9 === 0 && startedAt !== null
  const timeline = buildTimeline(rng, run, traces, endMs, outcomeForTimeline, injectCertError)

  return { run, traces, timeline, pendingCompletion }
}

export interface SeedData {
  tenants: Tenant[]
  scriptedRuns: ScriptedRun[]
}

/** Creates a brand-new "live" run (used for POST /runs and for the retry
 * action). Uses Math.random rather than the seeded rng since determinism
 * across reloads doesn't matter for runs created during a live session —
 * it self-completes a few seconds/minutes later just like a seeded RUNNING
 * run does. */
export function createScriptedRun(params: { tenant: Tenant; agent: AgentSeed; spec: RunSpec; attempt: number }): ScriptedRun {
  const rng: Rng = Math.random
  const { tenant, agent, spec, attempt } = params
  const id = `run_${shortId(rng, 14)}`
  const k8sJobName = `agent-run-${id.slice(4, 12)}`
  const idempotencyKey = `idem_${shortId(rng, 20)}`
  const createdAtMs = Date.now()
  const startedAtMs = createdAtMs + 1500
  const startedAt = new Date(startedAtMs).toISOString()
  const nodeName = `gke-agentplane-pool-${pick(rng, ['a', 'b', 'c'])}-${shortId(rng, 6)}`
  const rate = rateFor(spec.model)

  const completionInMs = intBetween(rng, 10_000, 26_000)
  const finalStatus = weightedPick<RunStatus>(rng, [
    ['SUCCEEDED', 78],
    ['FAILED', 14],
    ['TIMED_OUT', 8],
  ])
  const finalMessage =
    finalStatus === 'FAILED'
      ? 'Unrecoverable tool error after 3 retries'
      : finalStatus === 'TIMED_OUT'
        ? `Run exceeded configured timeout of ${spec.timeoutSeconds}s`
        : null
  const endMs = startedAtMs + completionInMs
  const pendingCompletion: PendingCompletion = {
    atMs: endMs,
    status: finalStatus,
    podPhase: finalStatus === 'SUCCEEDED' ? 'Succeeded' : 'Failed',
    exitCode: finalStatus === 'SUCCEEDED' ? 0 : finalStatus === 'TIMED_OUT' ? 124 : 1,
    message: finalMessage,
  }

  const run: RunDetail = {
    id,
    tenantId: tenant.id,
    tenantName: tenant.name,
    agentName: agent.name,
    status: 'RUNNING',
    createdAt: new Date(createdAtMs).toISOString(),
    startedAt,
    finishedAt: null,
    durationMs: null,
    k8sJobName,
    namespace: tenant.namespace,
    attempt,
    idempotencyKey,
    spec,
    podPhase: 'Running',
    exitCode: null,
    restartCount: 0,
    nodeName,
    message: null,
    stepCount: 0,
    tokenUsage: { prompt: 0, completion: 0, total: 0 },
    costUsd: 0,
  }

  const stepBudgetMs = Math.max(1000, endMs - startedAtMs - 400)
  const { traces, promptTokens, completionTokens } = buildTraces(
    rng,
    id,
    agent.tools,
    spec.model,
    startedAtMs + 600,
    stepBudgetMs,
    finalStatus === 'FAILED',
  )
  run.stepCount = traces.length
  run.tokenUsage = { prompt: promptTokens, completion: completionTokens, total: promptTokens + completionTokens }
  run.costUsd = Math.round(((promptTokens / 1000) * rate.inPer1k + (completionTokens / 1000) * rate.outPer1k) * 10000) / 10000

  const timeline = buildTimeline(rng, run, traces, endMs, { status: finalStatus, message: finalMessage }, false)

  return { run, traces, timeline, pendingCompletion }
}

export function buildSeedData(): SeedData {
  const rng = mulberry32(SEED)
  const tenants = makeTenants(rng)
  const scriptedRuns: ScriptedRun[] = []
  for (let i = 0; i < RUN_COUNT; i++) {
    scriptedRuns.push(buildOneRun(rng, i, tenants))
  }
  // Sort newest-created first to match typical API ordering.
  scriptedRuns.sort((a, b) => new Date(b.run.createdAt).getTime() - new Date(a.run.createdAt).getTime())
  return { tenants, scriptedRuns }
}

// Static reference data used to generate the seeded demo dataset: tenants,
// agent definitions, tool latency/error profiles, and model pricing. Kept
// separate from the generator (seed.ts) so the "flavor" of the fake fleet is
// easy to scan and tweak in one place.

export interface TenantSeed {
  name: string
  slug: string
  namespace: string
  quotaCpu: string
  quotaMemory: string
  maxConcurrentRuns: number
}

export const TENANT_SEEDS: TenantSeed[] = [
  { name: 'Northbeam Analytics', slug: 'northbeam', namespace: 'agentplane-northbeam', quotaCpu: '64', quotaMemory: '256Gi', maxConcurrentRuns: 24 },
  { name: 'Solace Robotics', slug: 'solace-robotics', namespace: 'agentplane-solace', quotaCpu: '96', quotaMemory: '384Gi', maxConcurrentRuns: 32 },
  { name: 'Cobalt Financial', slug: 'cobalt-financial', namespace: 'agentplane-cobalt', quotaCpu: '48', quotaMemory: '192Gi', maxConcurrentRuns: 16 },
  { name: 'Fenwick Logistics', slug: 'fenwick-logistics', namespace: 'agentplane-fenwick', quotaCpu: '32', quotaMemory: '128Gi', maxConcurrentRuns: 12 },
  { name: 'Meridian Health', slug: 'meridian-health', namespace: 'agentplane-meridian', quotaCpu: '80', quotaMemory: '320Gi', maxConcurrentRuns: 20 },
]

export interface ToolProfile {
  name: string
  baseLatencyMs: number
  jitterMs: number
  errorRate: number
}

export const TOOL_PROFILES: ToolProfile[] = [
  { name: 'web_search', baseLatencyMs: 420, jitterMs: 380, errorRate: 0.03 },
  { name: 'http_request', baseLatencyMs: 180, jitterMs: 420, errorRate: 0.06 },
  { name: 'sql_query', baseLatencyMs: 90, jitterMs: 900, errorRate: 0.04 },
  { name: 'vector_search', baseLatencyMs: 60, jitterMs: 140, errorRate: 0.01 },
  { name: 'code_exec', baseLatencyMs: 300, jitterMs: 1600, errorRate: 0.08 },
  { name: 'file_read', baseLatencyMs: 30, jitterMs: 60, errorRate: 0.01 },
  { name: 'file_write', baseLatencyMs: 40, jitterMs: 80, errorRate: 0.01 },
  { name: 'send_email', baseLatencyMs: 220, jitterMs: 200, errorRate: 0.02 },
  { name: 'slack_post', baseLatencyMs: 140, jitterMs: 160, errorRate: 0.02 },
  { name: 'k8s_exec', baseLatencyMs: 260, jitterMs: 500, errorRate: 0.05 },
  { name: 'pdf_extract', baseLatencyMs: 500, jitterMs: 700, errorRate: 0.03 },
  { name: 'calendar_lookup', baseLatencyMs: 90, jitterMs: 120, errorRate: 0.01 },
]

export interface ModelRate {
  model: string
  inPer1k: number
  outPer1k: number
}

export const MODEL_RATES: ModelRate[] = [
  { model: 'claude-opus-4-1', inPer1k: 0.015, outPer1k: 0.075 },
  { model: 'claude-sonnet-4-5', inPer1k: 0.003, outPer1k: 0.015 },
  { model: 'claude-haiku-4-5', inPer1k: 0.0008, outPer1k: 0.004 },
  { model: 'gpt-4o', inPer1k: 0.0025, outPer1k: 0.01 },
  { model: 'gpt-4o-mini', inPer1k: 0.00015, outPer1k: 0.0006 },
  { model: 'gemini-1.5-pro', inPer1k: 0.00125, outPer1k: 0.005 },
  { model: 'llama-3.1-70b-instruct', inPer1k: 0.0005, outPer1k: 0.0005 },
]

export interface AgentSeed {
  name: string
  image: string
  tools: string[]
  models: string[]
  promptSamples: string[]
}

export const AGENT_SEEDS: AgentSeed[] = [
  {
    name: 'research-agent',
    image: 'registry.agentplane.internal/agents/research-agent:1.6.2',
    tools: ['web_search', 'http_request', 'vector_search', 'file_write'],
    models: ['claude-sonnet-4-5', 'gpt-4o', 'gemini-1.5-pro'],
    promptSamples: [
      'Summarize the top 5 competitor pricing changes in the last quarter and cite sources.',
      'Research current best practices for zero-downtime Postgres migrations.',
      'Compile a brief on regulatory changes affecting cross-border data transfer in the EU.',
    ],
  },
  {
    name: 'support-triage',
    image: 'registry.agentplane.internal/agents/support-triage:2.3.0',
    tools: ['vector_search', 'http_request', 'slack_post', 'send_email'],
    models: ['claude-haiku-4-5', 'gpt-4o-mini'],
    promptSamples: [
      'Classify and route ticket #48213 to the correct on-call team.',
      'Draft a first-response reply for a customer reporting a billing discrepancy.',
      'Triage the incoming P1 alert and page the appropriate responder.',
    ],
  },
  {
    name: 'code-reviewer',
    image: 'registry.agentplane.internal/agents/code-reviewer:3.1.4',
    tools: ['code_exec', 'file_read', 'http_request'],
    models: ['claude-sonnet-4-5', 'claude-opus-4-1'],
    promptSamples: [
      'Review PR #1284 for correctness, security issues, and test coverage gaps.',
      'Run the test suite against the feature branch and summarize failures.',
      'Check the diff for hardcoded credentials or missing input validation.',
    ],
  },
  {
    name: 'sql-analyst',
    image: 'registry.agentplane.internal/agents/sql-analyst:1.2.1',
    tools: ['sql_query', 'vector_search', 'file_write'],
    models: ['gpt-4o', 'claude-sonnet-4-5'],
    promptSamples: [
      'Produce a weekly churn breakdown by plan tier from the warehouse.',
      'Find anomalous spend spikes in the billing_events table over the last 30 days.',
      'Generate a cohort retention query and explain the result set.',
    ],
  },
  {
    name: 'doc-summarizer',
    image: 'registry.agentplane.internal/agents/doc-summarizer:1.0.9',
    tools: ['pdf_extract', 'file_read', 'file_write'],
    models: ['claude-haiku-4-5', 'gemini-1.5-pro'],
    promptSamples: [
      'Summarize the attached vendor security questionnaire into key risks.',
      'Extract obligations and renewal dates from the uploaded MSA.',
      'Condense the 40-page incident postmortem into an executive summary.',
    ],
  },
  {
    name: 'invoice-parser',
    image: 'registry.agentplane.internal/agents/invoice-parser:2.0.3',
    tools: ['pdf_extract', 'sql_query', 'file_write'],
    models: ['gpt-4o-mini', 'claude-haiku-4-5'],
    promptSamples: [
      'Parse the uploaded invoice batch and reconcile against purchase orders.',
      'Flag line items that exceed the approved budget threshold.',
      'Extract vendor, amount, and due date from invoice INV-88213.',
    ],
  },
  {
    name: 'compliance-auditor',
    image: 'registry.agentplane.internal/agents/compliance-auditor:1.4.0',
    tools: ['sql_query', 'http_request', 'file_read', 'file_write'],
    models: ['claude-opus-4-1', 'claude-sonnet-4-5'],
    promptSamples: [
      'Audit access logs for the finance namespace against SOC 2 control CC6.1.',
      'Verify all production secrets were rotated within the last 90 days.',
      'Check that data retention policy is enforced for the analytics namespace.',
    ],
  },
  {
    name: 'ticket-router',
    image: 'registry.agentplane.internal/agents/ticket-router:1.8.5',
    tools: ['vector_search', 'slack_post', 'http_request'],
    models: ['gpt-4o-mini', 'claude-haiku-4-5'],
    promptSamples: [
      'Route new inbound tickets from the last hour to the correct queue.',
      'Deduplicate open tickets referencing the same outage.',
      'Escalate any ticket older than 4 hours with no response.',
    ],
  },
]

export const ENVIRONMENTS = ['production', 'staging', 'development'] as const
export type EnvironmentName = (typeof ENVIRONMENTS)[number]

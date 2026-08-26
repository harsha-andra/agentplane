import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DEMO_MODE } from '../config'
import * as store from '../mocks/store'
import type { Page, RunDetail, RunListParams, RunSummary } from '../types/api'
import { apiGet, apiPost, demoCall } from './http'

function fetchRuns(params: RunListParams): Promise<Page<RunSummary>> {
  if (DEMO_MODE) return demoCall(() => store.listRuns(params))
  return apiGet<Page<RunSummary>>('/runs', {
    page: params.page,
    size: params.size,
    status: params.status && params.status.length > 0 ? params.status.join(',') : undefined,
    tenantId: params.tenantId,
    q: params.q,
  })
}

function fetchRun(id: string): Promise<RunDetail> {
  if (DEMO_MODE) return demoCall(() => store.getRun(id))
  return apiGet<RunDetail>(`/runs/${encodeURIComponent(id)}`)
}

function cancelRunRequest(id: string): Promise<RunDetail> {
  if (DEMO_MODE) return demoCall(() => store.cancelRun(id))
  return apiPost<RunDetail>(`/runs/${encodeURIComponent(id)}/cancel`)
}

async function retryRunRequest(id: string): Promise<RunDetail> {
  if (DEMO_MODE) return demoCall(() => store.retryRun(id))
  // No dedicated retry endpoint in the contract: resubmit the original
  // run's spec via the documented POST /runs, exactly as an operator
  // re-triggering the same job would.
  const original = await apiGet<RunDetail>(`/runs/${encodeURIComponent(id)}`)
  return apiPost<RunDetail>('/runs', { tenantId: original.tenantId, spec: original.spec })
}

export const runKeys = {
  all: ['runs'] as const,
  list: (params: RunListParams) => ['runs', 'list', params] as const,
  detail: (id: string) => ['runs', 'detail', id] as const,
}

export function useRunsQuery(params: RunListParams, options?: { refetchInterval?: number | false }) {
  return useQuery({
    queryKey: runKeys.list(params),
    queryFn: () => fetchRuns(params),
    placeholderData: keepPreviousData,
    refetchInterval: options?.refetchInterval ?? false,
  })
}

export function useRunQuery(id: string | undefined, options?: { refetchInterval?: number | false }) {
  return useQuery({
    queryKey: runKeys.detail(id ?? ''),
    queryFn: () => fetchRun(id as string),
    enabled: !!id,
    refetchInterval: options?.refetchInterval ?? false,
  })
}

export function useCancelRunMutation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: cancelRunRequest,
    onSuccess: (run) => {
      qc.setQueryData(runKeys.detail(run.id), run)
      qc.invalidateQueries({ queryKey: runKeys.all })
    },
  })
}

export function useRetryRunMutation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: retryRunRequest,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: runKeys.all })
    },
  })
}

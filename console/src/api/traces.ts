import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { DEMO_MODE } from '../config'
import * as store from '../mocks/store'
import type { Page, Trace, TraceListParams, TraceRow } from '../types/api'
import { apiGet, demoCall } from './http'

/** Per-run traces backing the run detail step timeline — matches the given
 * contract's GET /runs/{id}/traces exactly. */
function fetchRunTraces(runId: string): Promise<Trace[]> {
  if (DEMO_MODE) return demoCall(() => store.getTraces(runId))
  return apiGet<Trace[]>(`/runs/${encodeURIComponent(runId)}/traces`)
}

export function useRunTracesQuery(runId: string | undefined) {
  return useQuery({
    queryKey: ['runs', 'traces', runId],
    queryFn: () => fetchRunTraces(runId as string),
    enabled: !!runId,
  })
}

/** Cross-run trace explorer backing /traces — an addition beyond the given
 * contract (see TraceRow doc comment in types/api.ts). */
function fetchTraceList(params: TraceListParams): Promise<Page<TraceRow>> {
  if (DEMO_MODE) return demoCall(() => store.listTraces(params))
  return apiGet<Page<TraceRow>>('/traces', {
    toolName: params.toolName,
    type: params.type,
    from: params.from,
    to: params.to,
    q: params.q,
    page: params.page,
    size: params.size,
  })
}

export function useTraceListQuery(params: TraceListParams) {
  return useQuery({
    queryKey: ['traces', 'list', params],
    queryFn: () => fetchTraceList(params),
    placeholderData: keepPreviousData,
  })
}

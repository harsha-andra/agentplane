import { useQuery } from '@tanstack/react-query'
import { DEMO_MODE } from '../config'
import * as store from '../mocks/store'
import type { Overview, ToolLatency } from '../types/api'
import { apiGet, demoCall } from './http'

function fetchOverview(): Promise<Overview> {
  if (DEMO_MODE) return demoCall(() => store.getOverview())
  return apiGet<Overview>('/analytics/overview')
}

function fetchToolLatency(days: number): Promise<ToolLatency[]> {
  if (DEMO_MODE) return demoCall(() => store.getToolLatency(days))
  return apiGet<ToolLatency[]>('/analytics/tool-latency', { days })
}

export function useOverviewQuery(options?: { refetchInterval?: number | false }) {
  return useQuery({
    queryKey: ['analytics', 'overview'],
    queryFn: fetchOverview,
    refetchInterval: options?.refetchInterval ?? 15_000,
  })
}

export function useToolLatencyQuery(days = 7) {
  return useQuery({
    queryKey: ['analytics', 'tool-latency', days],
    queryFn: () => fetchToolLatency(days),
  })
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { DEMO_MODE } from '../config'
import * as store from '../mocks/store'
import type { CreateTenantInput, Tenant } from '../types/api'
import { apiGet, apiPost, demoCall } from './http'

function fetchTenants(): Promise<Tenant[]> {
  if (DEMO_MODE) return demoCall(() => store.listTenants())
  return apiGet<Tenant[]>('/tenants')
}

function createTenantRequest(input: CreateTenantInput): Promise<Tenant> {
  if (DEMO_MODE) return demoCall(() => store.createTenant(input))
  return apiPost<Tenant>('/tenants', input)
}

export const tenantKeys = {
  all: ['tenants'] as const,
}

export function useTenantsQuery(options?: { refetchInterval?: number | false }) {
  return useQuery({
    queryKey: tenantKeys.all,
    queryFn: fetchTenants,
    refetchInterval: options?.refetchInterval ?? false,
  })
}

export function useCreateTenantMutation() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: createTenantRequest,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: tenantKeys.all })
    },
  })
}

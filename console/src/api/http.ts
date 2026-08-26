// Thin fetch wrapper used only when DEMO_MODE is off (a real control plane
// is reachable at API_BASE). Demo mode never hits the network — instead
// `demoCall` adds a small simulated latency around a synchronous mock-store
// call so loading states/skeletons behave like they would against a real
// backend.

import { API_BASE } from '../config'

export function demoCall<T>(fn: () => T): Promise<T> {
  const delayMs = 180 + Math.random() * 260
  return new Promise((resolve, reject) => {
    setTimeout(() => {
      try {
        resolve(fn())
      } catch (err) {
        reject(err instanceof Error ? err : new Error(String(err)))
      }
    }, delayMs)
  })
}

type QueryValue = string | number | boolean | undefined | null

function buildQuery(params?: Record<string, QueryValue>): string {
  if (!params) return ''
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

export async function apiGet<T>(path: string, params?: Record<string, QueryValue>): Promise<T> {
  const res = await fetch(`${API_BASE}${path}${buildQuery(params)}`)
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

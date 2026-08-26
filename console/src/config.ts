// Central runtime configuration. Keep this the single source of truth for
// environment-derived flags so pages never read import.meta.env directly.

/** Demo mode serves every endpoint from an in-memory mock layer (src/mocks).
 * Default ON so the app is fully self-contained when deployed to Vercel
 * without a live control plane behind it. Set VITE_DEMO_MODE=false to talk
 * to a real backend at VITE_API_BASE. */
export const DEMO_MODE = import.meta.env.VITE_DEMO_MODE !== 'false'

export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export const APP_NAME = 'AGENTPLANE'

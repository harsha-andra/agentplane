import { useState } from 'react'
import { ENVIRONMENTS, type EnvironmentName } from '../../mocks/fixtures'

const STORAGE_KEY = 'agentplane.environment'

function readInitial(): EnvironmentName {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored && (ENVIRONMENTS as readonly string[]).includes(stored)) return stored as EnvironmentName
  } catch {
    // ignore
  }
  return 'production'
}

/** Cosmetic environment selector, consistent with a real operator console —
 * it doesn't change which dataset is loaded (this demo always shows the
 * seeded fleet), but it persists across reloads like the theme does. */
export function EnvironmentSelector() {
  const [env, setEnv] = useState<EnvironmentName>(readInitial)

  return (
    <select
      className="select mono"
      style={{ width: 'auto', minWidth: 0, flexShrink: 0, fontWeight: 500 }}
      value={env}
      onChange={(e) => {
        const next = e.target.value as EnvironmentName
        setEnv(next)
        try {
          localStorage.setItem(STORAGE_KEY, next)
        } catch {
          // ignore
        }
      }}
      aria-label="Environment"
    >
      {ENVIRONMENTS.map((e) => (
        <option key={e} value={e}>
          {e}
        </option>
      ))}
    </select>
  )
}

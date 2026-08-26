// Deterministic categorical color assignment for chart series keyed by
// entity name (tool name, etc.) — colors follow the entity, never its rank,
// so filtering/sorting a chart never repaints the series that survive.
// Backed by the validated 8-hue categorical ramp defined in tokens.css.

const CATEGORICAL_VARS = [
  '--chart-cat-1',
  '--chart-cat-2',
  '--chart-cat-3',
  '--chart-cat-4',
  '--chart-cat-5',
  '--chart-cat-6',
  '--chart-cat-7',
  '--chart-cat-8',
] as const

export const CATEGORICAL_SLOT_COUNT = CATEGORICAL_VARS.length

function hashString(key: string): number {
  let hash = 0
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) | 0
  }
  return Math.abs(hash)
}

/** Returns a `var(--chart-cat-N)` CSS value for the given entity name.
 * Beyond the 8 validated slots, callers should fold extra entities into an
 * "Other" bucket rather than calling this — it never fabricates a 9th hue. */
export function categoricalColor(key: string): string {
  const idx = hashString(key) % CATEGORICAL_VARS.length
  return `var(${CATEGORICAL_VARS[idx]})`
}

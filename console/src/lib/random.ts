// Small deterministic PRNG so the seeded demo dataset is reproducible across
// reloads within a session and stable to reason about in tests. Not
// cryptographic — purely for generating plausible-looking fixture data.

export type Rng = () => number

/** mulberry32 — fast, tiny, good-enough distribution for fixture data. */
export function mulberry32(seed: number): Rng {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function pick<T>(rng: Rng, items: readonly T[]): T {
  return items[Math.floor(rng() * items.length)]
}

export function weightedPick<T>(rng: Rng, items: readonly (readonly [T, number])[]): T {
  const total = items.reduce((sum, [, w]) => sum + w, 0)
  let x = rng() * total
  for (const [item, w] of items) {
    x -= w
    if (x <= 0) return item
  }
  return items[items.length - 1][0]
}

export function intBetween(rng: Rng, min: number, max: number): number {
  return Math.floor(rng() * (max - min + 1)) + min
}

export function floatBetween(rng: Rng, min: number, max: number): number {
  return rng() * (max - min) + min
}

export function chance(rng: Rng, probability: number): boolean {
  return rng() < probability
}

const ID_ALPHABET = 'abcdefghijklmnopqrstuvwxyz0123456789'

export function shortId(rng: Rng, length = 8): string {
  let s = ''
  for (let i = 0; i < length; i++) s += ID_ALPHABET[Math.floor(rng() * ID_ALPHABET.length)]
  return s
}

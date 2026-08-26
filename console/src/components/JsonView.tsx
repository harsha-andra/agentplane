import type { ReactNode } from 'react'

const TOKEN_PATTERN = /("(?:\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\btrue\b|\bfalse\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g

function highlight(json: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  let key = 0
  TOKEN_PATTERN.lastIndex = 0
  while ((match = TOKEN_PATTERN.exec(json))) {
    if (match.index > lastIndex) nodes.push(json.slice(lastIndex, match.index))
    const token = match[0]
    let cls: string
    if (token.startsWith('"')) {
      cls = token.endsWith(':') ? 'json-key' : 'json-string'
    } else if (token === 'true' || token === 'false') {
      cls = 'json-bool'
    } else if (token === 'null') {
      cls = 'json-null'
    } else {
      cls = 'json-number'
    }
    nodes.push(
      <span key={key++} className={cls}>
        {token}
      </span>,
    )
    lastIndex = TOKEN_PATTERN.lastIndex
  }
  nodes.push(json.slice(lastIndex))
  return nodes
}

export interface JsonViewProps {
  value: unknown
  className?: string
}

/** Lightweight syntax-highlighted JSON — no dependency, no interactive
 * tree, just readable monospace with token coloring (keys/strings/numbers/
 * booleans/null), matching the terminal-adjacent aesthetic elsewhere. */
export function JsonView({ value, className }: JsonViewProps) {
  const json = JSON.stringify(value, null, 2) ?? 'null'
  return <pre className={`mono json-view ${className ?? ''}`}>{highlight(json)}</pre>
}

export interface CollapsibleJsonProps extends JsonViewProps {
  summary: ReactNode
  defaultOpen?: boolean
}

/** Same renderer behind a <details> disclosure, for step-by-step payloads
 * where showing everything expanded by default would be too dense. */
export function CollapsibleJson({ value, summary, defaultOpen = false, className }: CollapsibleJsonProps) {
  return (
    <details open={defaultOpen}>
      <summary style={{ cursor: 'pointer', fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', padding: '2px 0' }}>
        {summary}
      </summary>
      <div style={{ marginTop: 6 }}>
        <JsonView value={value} className={className} />
      </div>
    </details>
  )
}

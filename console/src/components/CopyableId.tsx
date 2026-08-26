import { useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { copyToClipboard } from '../lib/clipboard'
import { truncateId } from '../lib/format'

export interface CopyableIdProps {
  value: string
  /** Characters to keep from the front/back when truncating. Pass 0/0 (or
   * omit both) to render the full id. */
  head?: number
  tail?: number
  className?: string
}

/** Monospace id with truncation + a copy-to-clipboard affordance — used for
 * every run id / job name / idempotency key in the console. */
export function CopyableId({ value, head = 8, tail = 4, className }: CopyableIdProps) {
  const [copied, setCopied] = useState(false)
  const display = head || tail ? truncateId(value, head, tail) : value

  const onCopy = async (e: React.MouseEvent) => {
    e.stopPropagation()
    const ok = await copyToClipboard(value)
    if (ok) {
      setCopied(true)
      setTimeout(() => setCopied(false), 1400)
    }
  }

  return (
    <span className={`mono ${className ?? ''}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
      <span className="truncate" title={value}>
        {display}
      </span>
      <button
        type="button"
        className="copy-btn"
        onClick={onCopy}
        aria-label={copied ? 'Copied' : `Copy ${value}`}
        title={copied ? 'Copied' : 'Copy to clipboard'}
      >
        {copied ? <Check size={12} /> : <Copy size={12} />}
      </button>
    </span>
  )
}

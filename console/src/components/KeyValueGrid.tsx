import { Fragment, type ReactNode } from 'react'

export interface KeyValueItem {
  label: string
  value: ReactNode
}

export function KeyValueGrid({ items }: { items: KeyValueItem[] }) {
  return (
    <dl style={{ display: 'grid', gridTemplateColumns: 'auto 1fr', rowGap: 12, columnGap: 16, margin: 0 }}>
      {items.map((it) => (
        <Fragment key={it.label}>
          <dt className="text-tertiary" style={{ fontSize: 'var(--text-xs)', alignSelf: 'center' }}>
            {it.label}
          </dt>
          <dd className="mono" style={{ margin: 0, fontSize: 'var(--text-sm)', minWidth: 0, textAlign: 'right' }}>
            {it.value}
          </dd>
        </Fragment>
      ))}
    </dl>
  )
}

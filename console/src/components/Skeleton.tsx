export interface SkeletonProps {
  width?: string | number
  height?: string | number
  radius?: string
  className?: string
  style?: React.CSSProperties
}

export function Skeleton({ width = '100%', height = 14, radius, className, style }: SkeletonProps) {
  return (
    <span
      className={`skeleton ${className ?? ''}`}
      style={{ display: 'inline-block', width, height, borderRadius: radius, ...style }}
      aria-hidden="true"
    />
  )
}

export function SkeletonTableRows({ rows = 8, cols = 6 }: { rows?: number; cols?: number }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r}>
          {Array.from({ length: cols }).map((__, c) => (
            <td key={c}>
              <Skeleton height={14} width={c === 0 ? '70%' : '50%'} />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

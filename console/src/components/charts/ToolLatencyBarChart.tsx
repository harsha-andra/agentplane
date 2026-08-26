import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChartTooltip, type ChartTooltipProps } from './ChartTooltip'
import { categoricalColor } from '../../lib/chartColor'
import { formatDuration } from '../../lib/format'
import type { ToolLatency } from '../../types/api'

export interface ToolLatencyBarChartProps {
  data: ToolLatency[]
  limit?: number
}

/** p95 latency per tool. Color follows the tool name (a stable hash into
 * the categorical ramp), not the sort position, so re-filtering never
 * repaints the tools that remain on screen. */
export function ToolLatencyBarChart({ data, limit = 8 }: ToolLatencyBarChartProps) {
  const sorted = [...data].sort((a, b) => b.p95LatencyMs - a.p95LatencyMs).slice(0, limit)

  return (
    <div style={{ height: Math.max(200, sorted.length * 34) }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={sorted} layout="vertical" margin={{ top: 4, right: 20, left: 0, bottom: 4 }}>
          <CartesianGrid stroke="var(--chart-grid)" horizontal={false} />
          <XAxis
            type="number"
            tickFormatter={(v: number) => formatDuration(v)}
            stroke="var(--chart-axis)"
            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
            tickLine={false}
            axisLine={false}
          />
          <YAxis
            type="category"
            dataKey="toolName"
            width={108}
            stroke="var(--chart-axis)"
            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip
            cursor={{ fill: 'var(--bg-hover)' }}
            content={(props) => (
              <ChartTooltip {...(props as ChartTooltipProps)} valueFormatter={(v) => formatDuration(Number(v))} />
            )}
          />
          <Bar dataKey="p95LatencyMs" name="p95 latency" radius={[0, 4, 4, 0]} maxBarSize={16}>
            {sorted.map((d) => (
              <Cell key={d.toolName} fill={categoricalColor(d.toolName)} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

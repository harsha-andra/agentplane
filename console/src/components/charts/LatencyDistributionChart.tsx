import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChartTooltip, type ChartTooltipProps } from './ChartTooltip'

export interface LatencyBucket {
  label: string
  count: number
}

export interface LatencyDistributionChartProps {
  data: LatencyBucket[]
}

/** Single-series histogram of call latency — one hue is correct here (a
 * magnitude count, not an identity per category). */
export function LatencyDistributionChart({ data }: LatencyDistributionChartProps) {
  return (
    <div style={{ height: 240 }}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -20, bottom: 8 }}>
          <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
          <XAxis
            dataKey="label"
            stroke="var(--chart-axis)"
            tick={{ fontSize: 10, fill: 'var(--chart-axis)' }}
            tickLine={false}
            axisLine={false}
            interval={0}
            angle={-30}
            textAnchor="end"
            height={44}
          />
          <YAxis
            allowDecimals={false}
            stroke="var(--chart-axis)"
            tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
            tickLine={false}
            axisLine={false}
            width={30}
          />
          <Tooltip cursor={{ fill: 'var(--bg-hover)' }} content={(props) => <ChartTooltip {...(props as ChartTooltipProps)} />} />
          <Bar dataKey="count" name="Calls" fill="var(--chart-cat-1)" radius={[4, 4, 0, 0]} maxBarSize={40} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

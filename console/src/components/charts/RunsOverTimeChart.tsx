import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ChartTooltip, type ChartTooltipProps } from './ChartTooltip'
import { ChartLegend } from './ChartLegend'
import { formatClockTime } from '../../lib/format'
import type { Overview } from '../../types/api'

export interface RunsOverTimeChartProps {
  data: Overview['runsOverTime']
}

/** Stacked area of runs finished per hour, by outcome. Status colors match
 * the badges used everywhere else (StatusBadge / tokens.css --status-*). */
export function RunsOverTimeChart({ data }: RunsOverTimeChartProps) {
  return (
    <div>
      <div style={{ height: 240 }}>
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart data={data} margin={{ top: 8, right: 8, left: -20, bottom: 0 }}>
            <defs>
              <linearGradient id="fillSucceeded" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--status-succeeded)" stopOpacity={0.35} />
                <stop offset="95%" stopColor="var(--status-succeeded)" stopOpacity={0.02} />
              </linearGradient>
              <linearGradient id="fillFailed" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--status-failed)" stopOpacity={0.35} />
                <stop offset="95%" stopColor="var(--status-failed)" stopOpacity={0.02} />
              </linearGradient>
              <linearGradient id="fillCancelled" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="var(--status-cancelled)" stopOpacity={0.35} />
                <stop offset="95%" stopColor="var(--status-cancelled)" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="var(--chart-grid)" vertical={false} />
            <XAxis
              dataKey="ts"
              tickFormatter={(v: string) => formatClockTime(v)}
              stroke="var(--chart-axis)"
              tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
              tickLine={false}
              axisLine={false}
              minTickGap={40}
            />
            <YAxis
              allowDecimals={false}
              stroke="var(--chart-axis)"
              tick={{ fontSize: 11, fill: 'var(--chart-axis)' }}
              tickLine={false}
              axisLine={false}
              width={30}
            />
            <Tooltip
              cursor={{ stroke: 'var(--border-strong)', strokeWidth: 1 }}
              content={(props) => (
                <ChartTooltip {...(props as ChartTooltipProps)} labelFormatter={(l) => formatClockTime(String(l))} />
              )}
            />
            <Area type="monotone" dataKey="succeeded" name="Succeeded" stackId="a" stroke="var(--status-succeeded)" fill="url(#fillSucceeded)" strokeWidth={1.5} />
            <Area type="monotone" dataKey="failed" name="Failed" stackId="a" stroke="var(--status-failed)" fill="url(#fillFailed)" strokeWidth={1.5} />
            <Area type="monotone" dataKey="cancelled" name="Cancelled" stackId="a" stroke="var(--status-cancelled)" fill="url(#fillCancelled)" strokeWidth={1.5} />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      <div style={{ marginTop: 10 }}>
        <ChartLegend
          items={[
            { label: 'Succeeded', color: 'var(--status-succeeded)' },
            { label: 'Failed', color: 'var(--status-failed)' },
            { label: 'Cancelled', color: 'var(--status-cancelled)' },
          ]}
        />
      </div>
    </div>
  )
}

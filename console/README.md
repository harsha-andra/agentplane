# AGENTPLANE Console

The operator console for **AGENTPLANE** — a Kubernetes control plane for LLM
agent workloads. Built as a portfolio piece: a dense, precise operator UI in
the spirit of Grafana / Temporal UI / the Vercel dashboard, not a toy CRUD app.

The centerpiece is `/runs/:id` — a run detail page with a **live log tail**:
status is pushed to the browser as it happens (Server-Sent Events against
`GET /api/v1/runs/{id}/events`), not polled for.

## Quick start

```bash
npm install
npm run dev       # http://localhost:5173, demo mode on by default
npm run build     # type-checks (tsc -b, strict) then builds to dist/
npm test -- --run # vitest, single run
```

Node 22 / npm 10. No backend required to run it — see **Demo mode** below.

## Demo mode

`src/config.ts` exports:

```ts
export const DEMO_MODE = import.meta.env.VITE_DEMO_MODE !== 'false'
export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'
```

Demo mode is **on by default** so the console is fully self-contained when
deployed (Vercel, a static host, a laptop with no network) — there is no real
Spring Boot control plane behind the live deployment. Every `api/*.ts` module
branches on `DEMO_MODE`: on, it calls straight into an in-memory mock store
(`src/mocks/store.ts`); off, it calls the real REST/SSE endpoints in
`src/api/http.ts` / `src/api/realEventSource.ts`. Nothing else in the app
(components, hooks, pages) knows or cares which mode it's in — they only see
`RunDetail`, `RunEvent`, etc.

To point the console at a real backend: set `VITE_DEMO_MODE=false` and
`VITE_API_BASE=https://your-control-plane/api/v1` at build time.

### What the mock layer does

- **Seeds a plausible fleet** (`src/mocks/seed.ts` + `fixtures.ts`): 5 tenants,
  8 agent types, ~80 runs with a realistic status mix (mostly SUCCEEDED, a
  meaningful slice RUNNING/PENDING/SCHEDULED, some FAILED/CANCELLED/TIMED_OUT),
  and ~500 generated trace steps (tool calls, LLM calls, decisions, the
  occasional TLS/cert error for flavor).
- **Answers every endpoint with real filtering/pagination/sorting**
  (`src/mocks/store.ts`) — status/tenant/free-text filters, page/size,
  tool-name + time-window filters for the trace explorer — computed against
  the seeded data, not hand-authored fixtures per screen.
- **Simulates the SSE log tail** (`src/mocks/fakeEventSource.ts`): every run's
  full event timeline is precomputed at seed time, including — for runs that
  are still RUNNING — events whose timestamp is in the *future*. The fake
  transport just reveals events whose time has now arrived and replays
  history instantly for finished runs, so a RUNNING run genuinely progresses
  and eventually completes (status flips, duration fills in) the longer the
  demo runs, with no timers required in the store itself. It also injects
  occasional transient disconnects so the reconnect/backoff UI has something
  to show off.
- **Simulates network latency** (`src/api/http.ts` → `demoCall`) so skeleton
  loaders and loading states look like they're talking to a real backend
  instead of resolving instantly.

## Project structure

```
src/
  api/            React Query hooks + the DEMO_MODE/real branch per resource
    eventStream.ts        picks the fake or real SSE transport
    eventStreamTypes.ts   shared transport interface (both sides implement this)
    realEventSource.ts    wraps native EventSource
    http.ts                fetch + simulated demo latency
    runs.ts / traces.ts / tenants.ts / analytics.ts
  mocks/          the entire demo-mode backend
    fixtures.ts    tenants, agents, tools, model pricing — the "flavor" data
    seed.ts        generates the seeded dataset + per-run event timelines
    store.ts       in-memory query layer: filter/paginate/sort/mutate
    fakeEventSource.ts   simulated SSE transport
    health.ts      backend health for the cluster pill + Settings page
  hooks/
    useRunEventStream.ts   SSE consumer: connection state machine +
                           exponential backoff reconnect, pause/resume
    useTheme.ts / useDebouncedValue.ts
  components/     shared UI: StatusBadge, StatTile, JsonView, LiveLogTail,
                  StepTimeline, charts/, layout/ (shell, sidebar, topbar)
  pages/          one folder per route (overview, runs, traces, tenants,
                  analytics, settings)
  styles/         tokens.css (design tokens) + base.css + components.css +
                  layout.css — plain CSS, no component library
  types/api.ts    the API contract types, shared by real and mock code paths
```

## Design decisions

**Styling.** Plain CSS + CSS Modules over a component library, on purpose —
the brief is to demonstrate real CSS/React competence. `styles/tokens.css` is
the single source of truth for color scales, spacing, radii, type scale, and
status/severity colors; dark is the default theme (it's an infra tool) with
light fully supported via the same token set. Status colors are defined once
and consumed everywhere (`StatusBadge`, charts, the K8s panel) so they never
drift.

**Charts.** Recharts, but colored deliberately rather than defaulting to its
palette: run-outcome colors reuse the exact `--status-*` tokens so a stacked
area chart's "Succeeded" band is the same green as the status badge; tool
latency bars use a validated 8-color categorical ramp assigned by a stable
hash of the tool's name (`lib/chartColor.ts`) so re-filtering never repaints
a tool that stays on screen.

**The SSE hook (`useRunEventStream`).** Transport-agnostic by design — a
tiny `OpenEventStream` interface (`onOpen`/`onEvent`/`onError` in,
`close()` out) that both the real `EventSource` wrapper and the demo
simulator implement identically, so the hook (and its tests) don't know or
care which one is behind it. The hook owns an explicit
connecting → live → reconnecting → closed state machine with exponential
backoff + jitter and a capped retry count, rather than relying on the
browser's built-in (opaque, unbounded) EventSource retry. A terminal-phase
event (`phase: COMPLETED | FAILED | CANCELLED | TIMED_OUT`) closes the
stream deliberately instead of waiting for the transport to error out.

**Extensions beyond the given API contract.** Two, both documented at the
point of use:
- `GET /traces` (with `toolName`/`type`/`from`/`to`/`q`/`page`/`size`) backs
  the cross-run trace explorer — the given contract only scopes traces to a
  single run (`GET /runs/{id}/traces`, used as-is for the run detail step
  timeline). See the `TraceRow`/`TraceListParams` doc comment in
  `types/api.ts`.
- Retry re-submits a run's original spec via the documented `POST /runs`
  (fetching the original via `GET /runs/{id}` first) rather than inventing a
  `/retry` endpoint.

**Responsive to 360px.** The left rail becomes an off-canvas drawer below
860px; the top bar collapses progressively (the environment select and
cluster-health pill shrink/hide their labels, the search field narrows)
rather than wrapping into a second row.

## Tests

`npm test -- --run` (Vitest + Testing Library):
- `mocks/store.test.ts` — filtering (status/tenant/free-text), pagination
  (no gaps/overlap), sorting, against the real seeded store.
- `components/StatusBadge.test.tsx` — label + color/pulse mapping per status.
- `hooks/useRunEventStream.test.ts` — the reconnect state machine: backoff
  timing, attempt counting, giving up after `maxAttempts`, resetting on a
  successful reconnect, and closing (without retrying) on a terminal-phase
  event. Uses a mocked transport + fake timers.
- `pages/runs/RunsListPage.test.tsx` — a full page render through
  `QueryClientProvider` + `MemoryRouter`, exercising the real demo-mode data
  path end to end.

## Deploying

`vercel.json` rewrites everything to `/index.html` (client-side routing) and
points at `dist/` — `npm run build` is the Vercel build command. Demo mode
means it needs nothing else configured to work.

## Known gaps

- The `/traces` explorer fetches up to 500 rows per filter combination and
  paginates/sorts client-side rather than against a paged backend query —
  fine at this dataset size, wouldn't scale as written to a truly large
  trace volume.
- No auth/RBAC — out of scope for a portfolio console.
- The environment selector (production/staging/development) is cosmetic; it
  doesn't switch datasets.

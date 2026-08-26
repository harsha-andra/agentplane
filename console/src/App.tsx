import { lazy, Suspense } from 'react'
import { Route, Routes } from 'react-router-dom'
import { AppShell } from './components/layout/AppShell'

const OverviewPage = lazy(() => import('./pages/overview/OverviewPage').then((m) => ({ default: m.OverviewPage })))
const RunsListPage = lazy(() => import('./pages/runs/RunsListPage').then((m) => ({ default: m.RunsListPage })))
const RunDetailPage = lazy(() => import('./pages/runs/RunDetailPage').then((m) => ({ default: m.RunDetailPage })))
const TracesPage = lazy(() => import('./pages/traces/TracesPage').then((m) => ({ default: m.TracesPage })))
const TenantsPage = lazy(() => import('./pages/tenants/TenantsPage').then((m) => ({ default: m.TenantsPage })))
const AnalyticsPage = lazy(() => import('./pages/analytics/AnalyticsPage').then((m) => ({ default: m.AnalyticsPage })))
const SettingsPage = lazy(() => import('./pages/settings/SettingsPage').then((m) => ({ default: m.SettingsPage })))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage').then((m) => ({ default: m.NotFoundPage })))

function App() {
  return (
    <Suspense fallback={null}>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<OverviewPage />} />
          <Route path="/runs" element={<RunsListPage />} />
          <Route path="/runs/:id" element={<RunDetailPage />} />
          <Route path="/traces" element={<TracesPage />} />
          <Route path="/tenants" element={<TenantsPage />} />
          <Route path="/analytics" element={<AnalyticsPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  )
}

export default App

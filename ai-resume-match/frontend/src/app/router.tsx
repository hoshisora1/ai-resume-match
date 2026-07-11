import { createBrowserRouter, type RouteObject } from 'react-router'

import { AnalysesPage } from '../features/analyses/AnalysesPage'
import { NewAnalysisPage } from '../features/analyses/NewAnalysisPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AppShell } from './AppShell'
import { ErrorBoundary } from './ErrorBoundary'
import { PlaceholderPage } from './routeElements'

export const appRoutes = [
  {
    path: '/',
    element: (
      <ErrorBoundary>
        <AppShell />
      </ErrorBoundary>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'analyses', element: <AnalysesPage /> },
      { path: 'analyses/new', element: <NewAnalysisPage /> },
      { path: 'analyses/:taskId', element: <PlaceholderPage title="分析详情" /> },
    ],
  },
] satisfies RouteObject[]

export type AppRouter = ReturnType<typeof createBrowserRouter>

export function createAppBrowserRouter() {
  return createBrowserRouter(appRoutes)
}

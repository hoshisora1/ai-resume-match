import { createBrowserRouter, type RouteObject } from 'react-router'

import { AnalysesPage } from '../features/analyses/AnalysesPage'
import { AnalysisDetailPage } from '../features/analyses/AnalysisDetailPage'
import { NewAnalysisPage } from '../features/analyses/NewAnalysisPage'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AppShell } from './AppShell'
import { ErrorBoundary } from './ErrorBoundary'

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
      { path: 'analyses/:taskId', element: <AnalysisDetailPage /> },
    ],
  },
] satisfies RouteObject[]

export type AppRouter = ReturnType<typeof createBrowserRouter>

export function createAppBrowserRouter() {
  return createBrowserRouter(appRoutes)
}

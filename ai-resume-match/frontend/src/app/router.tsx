import { createBrowserRouter, type RouteObject } from 'react-router'

import { AppShell } from './AppShell'
import { ErrorBoundary } from './ErrorBoundary'
import {
  AnalysesRoutePage,
  AnalysisDetailRoutePage,
  DashboardRoutePage,
  NewAnalysisRoutePage,
} from './LazyRoutePages'

export const appRoutes = [
  {
    path: '/',
    element: (
      <ErrorBoundary>
        <AppShell />
      </ErrorBoundary>
    ),
    children: [
      { index: true, element: <DashboardRoutePage /> },
      { path: 'analyses', element: <AnalysesRoutePage /> },
      { path: 'analyses/new', element: <NewAnalysisRoutePage /> },
      { path: 'analyses/:taskId', element: <AnalysisDetailRoutePage /> },
    ],
  },
] satisfies RouteObject[]

export type AppRouter = ReturnType<typeof createBrowserRouter>

export function createAppBrowserRouter() {
  return createBrowserRouter(appRoutes)
}

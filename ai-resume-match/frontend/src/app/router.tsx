import { createBrowserRouter, type RouteObject } from 'react-router'

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
      { index: true, element: <PlaceholderPage title="分析总览" /> },
      { path: 'analyses', element: <PlaceholderPage title="分析历史" /> },
      { path: 'analyses/new', element: <PlaceholderPage title="新建分析" /> },
      { path: 'analyses/:taskId', element: <PlaceholderPage title="分析详情" /> },
    ],
  },
] satisfies RouteObject[]

export type AppRouter = ReturnType<typeof createBrowserRouter>

export function createAppBrowserRouter() {
  return createBrowserRouter(appRoutes)
}

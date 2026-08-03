import { lazy, Suspense, type ComponentType, type LazyExoticComponent } from 'react'

import { AsyncState } from '../shared/components/AsyncState'

const DashboardPage = lazy(() =>
  import('../features/dashboard/DashboardPage').then((module) => ({
    default: module.DashboardPage,
  })),
)
const AnalysesPage = lazy(() =>
  import('../features/analyses/AnalysesPage').then((module) => ({
    default: module.AnalysesPage,
  })),
)
const NewAnalysisPage = lazy(() =>
  import('../features/analyses/NewAnalysisPage').then((module) => ({
    default: module.NewAnalysisPage,
  })),
)
const AnalysisDetailPage = lazy(() =>
  import('../features/analyses/AnalysisDetailPage').then((module) => ({
    default: module.AnalysisDetailPage,
  })),
)

function LazyPage({ page: Page }: { page: LazyExoticComponent<ComponentType> }) {
  return (
    <Suspense fallback={<AsyncState label="正在加载页面" state="loading" />}>
      <Page />
    </Suspense>
  )
}

export function DashboardRoutePage() {
  return <LazyPage page={DashboardPage} />
}

export function AnalysesRoutePage() {
  return <LazyPage page={AnalysesPage} />
}

export function NewAnalysisRoutePage() {
  return <LazyPage page={NewAnalysisPage} />
}

export function AnalysisDetailRoutePage() {
  return <LazyPage page={AnalysisDetailPage} />
}

import { useNavigate } from 'react-router'

import { AsyncState } from '../../shared/components/AsyncState'
import { Button } from '../../shared/components/Button'
import {
  AnalysisTable,
  AnalysisTableSkeleton,
} from '../analyses/AnalysisTable'
import { useAnalysesQuery } from '../analyses/useAnalysesQuery'
import { useAnalysisSummaryQuery } from './useAnalysisSummaryQuery'
import './dashboard.css'

function MetricsSkeleton() {
  return (
    <div
      aria-busy="true"
      aria-label="正在加载分析概况"
      className="metric-grid"
      data-testid="dashboard-metrics-skeleton"
      role="status"
    >
      {Array.from({ length: 3 }, (_, index) => (
        <div aria-hidden="true" className="metric-panel" key={index}>
          <span className="metric-panel__skeleton-label" />
          <span className="metric-panel__skeleton-value" />
        </div>
      ))}
    </div>
  )
}

export function DashboardPage() {
  const navigate = useNavigate()
  const summaryQuery = useAnalysisSummaryQuery()
  const recentQuery = useAnalysesQuery({
    status: undefined,
    page: 0,
    size: 5,
  })

  return (
    <div className="dashboard-page">
      <header className="dashboard-page__header">
        <h1>分析总览</h1>
      </header>

      <section
        aria-labelledby="dashboard-summary-heading"
        className="dashboard-section"
      >
        <div className="dashboard-section__heading">
          <h2 id="dashboard-summary-heading">分析概况</h2>
        </div>
        <div className="dashboard-summary-slot">
          {summaryQuery.isPending ? <MetricsSkeleton /> : null}
          {summaryQuery.isError ? (
            <AsyncState
              className="dashboard-summary-state"
              description="请检查服务连接后重新加载概况。"
              onRetry={() => void summaryQuery.refetch()}
              state="error"
              title="概况加载失败"
            />
          ) : null}
          {summaryQuery.isSuccess ? (
            <dl className="metric-grid">
              <div className="metric-panel">
                <dt>总分析</dt>
                <dd>{summaryQuery.data.totalCount}</dd>
              </div>
              <div className="metric-panel">
                <dt>已完成</dt>
                <dd>{summaryQuery.data.successCount}</dd>
              </div>
              <div className="metric-panel metric-panel--score">
                <dt>平均匹配度</dt>
                <dd>
                  {summaryQuery.data.averageMatchScore === null
                    ? '—'
                    : summaryQuery.data.averageMatchScore.toFixed(1)}
                </dd>
              </div>
            </dl>
          ) : null}
        </div>
      </section>

      <section
        aria-labelledby="dashboard-recent-heading"
        className="dashboard-section dashboard-section--recent"
      >
        <div className="dashboard-section__heading">
          <h2 id="dashboard-recent-heading">最近分析</h2>
          <Button
            className="dashboard-section__history-action"
            onClick={() => navigate('/analyses')}
            variant="secondary"
          >
            查看全部
          </Button>
        </div>

        <div className="dashboard-recent-slot">
          {recentQuery.isPending ? (
            <AnalysisTableSkeleton
              label="正在加载最近分析"
              recent
              rowCount={5}
            />
          ) : null}
          {recentQuery.isError ? (
            <AsyncState
              className="dashboard-recent-state"
              description="请检查服务连接后重试。"
              onRetry={() => void recentQuery.refetch()}
              state="error"
              title="近期记录加载失败"
            />
          ) : null}
          {recentQuery.isSuccess && recentQuery.data.items.length === 0 ? (
            <div className="dashboard-recent-state dashboard-empty-action">
              <AsyncState
                description="创建分析后，最近记录会显示在这里。"
                state="empty"
                title="还没有近期分析"
              />
              <Button onClick={() => navigate('/analyses/new')}>新建分析</Button>
            </div>
          ) : null}
          {recentQuery.isSuccess && recentQuery.data.items.length > 0 ? (
            <AnalysisTable
              accessibleName="最近五条分析"
              items={recentQuery.data.items}
              recent
            />
          ) : null}
        </div>
      </section>
    </div>
  )
}

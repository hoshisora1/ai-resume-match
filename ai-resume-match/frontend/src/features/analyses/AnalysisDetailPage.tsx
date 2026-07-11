import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, ArrowLeft, RefreshCw, RotateCcw } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { Link, useParams } from 'react-router'

import { getMatchReport, retryAnalysisTask } from '../../shared/api/analyses'
import { ApiError } from '../../shared/api/client'
import type { AnalysisTask } from '../../shared/api/schemas'
import { AsyncState } from '../../shared/components/AsyncState'
import { Button } from '../../shared/components/Button'
import { CopyReference } from '../../shared/components/CopyReference'
import { StatusBadge } from '../../shared/components/StatusBadge'
import { analysisSummaryQueryKey } from '../dashboard/useAnalysisSummaryQuery'
import { MatchReport } from '../reports/MatchReport'
import { analysisReportQueryKey } from '../reports/reportQuery'
import { formatAnalysisDate } from './analysisPresentation'
import { analysisTaskQueryKey, useAnalysisTask } from './useAnalysisTask'
import './analysis-detail.css'

const CANONICAL_POSITIVE_DECIMAL = /^[1-9]\d*$/

function parseTaskId(value: string | undefined) {
  if (value === undefined || !CANONICAL_POSITIVE_DECIMAL.test(value)) {
    return undefined
  }

  const taskId = Number(value)
  return Number.isSafeInteger(taskId) && taskId > 0 ? taskId : undefined
}

function requestIdFrom(error: unknown) {
  if (!(error instanceof ApiError)) {
    return undefined
  }

  const requestId = error.requestId?.trim()
  return requestId ? requestId : undefined
}

function failureSummary(task: AnalysisTask) {
  if (task.status === 'CANCELLED') {
    return '任务已取消。'
  }

  if (task.status === 'FAILED') {
    return '任务未能完成。'
  }

  switch (task.failureCode) {
    case 'AI_UNAVAILABLE':
      return '分析服务暂时不可用。'
    case 'REPORT_PARSE_FAILED':
      return '生成的报告无法验证。'
    case 'SOURCE_DATA_MISSING':
      return '分析所需的数据不可用。'
    default:
      return '任务未能完成。'
  }
}

function AnalysisDetailHeader() {
  return (
    <header className="analysis-detail__header">
      <Link className="analysis-detail__back" to="/analyses">
        <ArrowLeft aria-hidden="true" size={17} />
        <span>返回分析历史</span>
      </Link>
      <h1 id="analysis-detail-heading">分析详情</h1>
    </header>
  )
}

function MetadataDate({ value }: { value: string | null }) {
  if (value === null) {
    return <>—</>
  }

  return <time dateTime={value}>{formatAnalysisDate(value)}</time>
}

function TaskMetadata({ task }: { task: AnalysisTask }) {
  return (
    <section
      aria-labelledby="analysis-task-metadata-heading"
      className="analysis-detail__metadata"
    >
      <h2 id="analysis-task-metadata-heading">任务信息</h2>
      <dl>
        <div>
          <dt>岗位</dt>
          <dd>{task.jobTitle?.trim() || '—'}</dd>
        </div>
        <div>
          <dt>简历文件</dt>
          <dd>{task.resumeFileName?.trim() || '—'}</dd>
        </div>
        <div>
          <dt>任务 ID</dt>
          <dd>{task.taskId}</dd>
        </div>
        <div>
          <dt>状态</dt>
          <dd>
            <StatusBadge status={task.status} />
          </dd>
        </div>
        <div>
          <dt>尝试次数</dt>
          <dd>
            {task.attemptCount} / {task.maxAttempts}
          </dd>
        </div>
        <div>
          <dt>创建时间</dt>
          <dd>
            <MetadataDate value={task.createdAt} />
          </dd>
        </div>
        <div>
          <dt>开始时间</dt>
          <dd>
            <MetadataDate value={task.startedAt} />
          </dd>
        </div>
        <div>
          <dt>完成时间</dt>
          <dd>
            <MetadataDate value={task.completedAt} />
          </dd>
        </div>
        <div>
          <dt>下次重试</dt>
          <dd>
            <MetadataDate value={task.nextRetryAt} />
          </dd>
        </div>
      </dl>
    </section>
  )
}

interface TaskStatusSectionProps {
  onRetry: () => void
  retryError: unknown
  retryPending: boolean
  task: AnalysisTask
}

function TaskStatusSection({
  onRetry,
  retryError,
  retryPending,
  task,
}: TaskStatusSectionProps) {
  const retryRequestId = requestIdFrom(retryError)

  if (task.status === 'PENDING') {
    return (
      <section className="analysis-detail__status" data-status={task.status}>
        <h2>任务正在排队</h2>
        <p>等待分析服务处理。</p>
      </section>
    )
  }

  if (task.status === 'RUNNING') {
    return (
      <section className="analysis-detail__status" data-status={task.status}>
        <h2>正在生成匹配报告</h2>
        <p>简历与岗位信息正在分析中。</p>
      </section>
    )
  }

  if (task.status === 'FAILED_RETRYABLE') {
    return (
      <section className="analysis-detail__status" data-status={task.status}>
        <h2>本次分析可以重试</h2>
        <p>{failureSummary(task)}</p>
        <p className="analysis-detail__attempts">
          已尝试 {task.attemptCount} / {task.maxAttempts} 次
        </p>
        <div className="analysis-detail__actions">
          <Button loading={retryPending} onClick={onRetry}>
            <RotateCcw aria-hidden="true" size={17} />
            <span>重新分析</span>
          </Button>
          <CopyReference label="任务 ID" value={String(task.taskId)} />
        </div>
        {retryError !== null ? (
          <div className="analysis-detail__inline-error" role="alert">
            <p>重新分析失败，请稍后重试。</p>
            {retryRequestId !== undefined ? (
              <CopyReference label="请求 ID" value={retryRequestId} />
            ) : null}
          </div>
        ) : null}
      </section>
    )
  }

  if (task.status === 'SUCCESS') {
    return (
      <section className="analysis-detail__status" data-status={task.status}>
        <h2>分析已完成</h2>
        <p>匹配报告已生成。</p>
        <div className="analysis-detail__actions">
          <CopyReference label="任务 ID" value={String(task.taskId)} />
        </div>
      </section>
    )
  }

  const heading =
    task.status === 'CANCELLED' ? '任务已取消' : '分析未能完成'

  return (
    <section className="analysis-detail__status" data-status={task.status}>
      <h2>{heading}</h2>
      <p>{failureSummary(task)}</p>
      <div className="analysis-detail__actions">
        <CopyReference label="任务 ID" value={String(task.taskId)} />
      </div>
    </section>
  )
}

function TaskConnectionBanner({
  error,
  isRefreshing,
  onRefresh,
}: {
  error: unknown
  isRefreshing: boolean
  onRefresh: () => void
}) {
  const requestId = requestIdFrom(error)

  return (
    <div className="analysis-detail__connection" role="alert">
      <div className="analysis-detail__connection-message">
        <AlertTriangle aria-hidden="true" size={20} />
        <p>连接中断，当前显示上次获取的任务状态。</p>
      </div>
      <div className="analysis-detail__connection-actions">
        <Button
          loading={isRefreshing}
          onClick={onRefresh}
          variant="secondary"
        >
          <RefreshCw aria-hidden="true" size={16} />
          <span>刷新任务状态</span>
        </Button>
        {requestId !== undefined ? (
          <CopyReference label="请求 ID" value={requestId} />
        ) : null}
      </div>
    </div>
  )
}

function AnalysisReportSection({ taskId }: { taskId: number }) {
  const reportQuery = useQuery({
    queryKey: analysisReportQueryKey(taskId),
    queryFn: ({ signal }) => getMatchReport(taskId, signal),
  })
  const requestId = requestIdFrom(reportQuery.error)

  return (
    <section aria-label="匹配报告" className="analysis-detail__report">
      <h2>报告内容</h2>
      {reportQuery.isPending ? (
        <AsyncState label="正在加载匹配报告" state="loading" />
      ) : null}
      {reportQuery.isError && reportQuery.data === undefined ? (
        <div className="analysis-detail__report-error">
          <AsyncState
            description="任务已经完成，但报告暂时无法读取。"
            onRetry={() => void reportQuery.refetch()}
            state="error"
            title="匹配报告加载失败"
          />
          {requestId !== undefined ? (
            <CopyReference label="请求 ID" value={requestId} />
          ) : null}
        </div>
      ) : null}
      {reportQuery.data !== undefined ? (
        <>
          {reportQuery.isError ? (
            <div className="analysis-detail__report-warning" role="alert">
              <p>报告刷新失败，当前显示上次读取的内容。</p>
              <Button
                loading={reportQuery.isFetching}
                onClick={() => void reportQuery.refetch()}
                variant="secondary"
              >
                <RefreshCw aria-hidden="true" size={16} />
                <span>刷新报告</span>
              </Button>
              {requestId !== undefined ? (
                <CopyReference label="请求 ID" value={requestId} />
              ) : null}
            </div>
          ) : null}
          <MatchReport report={reportQuery.data} />
        </>
      ) : null}
    </section>
  )
}

function ValidAnalysisDetail({ taskId }: { taskId: number }) {
  const queryClient = useQueryClient()
  const taskQuery = useAnalysisTask(taskId)
  const retryInFlightRef = useRef(false)
  const retryControllerRef = useRef<AbortController | null>(null)
  const retryMutation = useMutation({
    mutationKey: ['retry-analysis-task', taskId],
    mutationFn: () => {
      const controller = new AbortController()
      retryControllerRef.current = controller
      return retryAnalysisTask(taskId, controller.signal)
    },
    onSuccess: async (task) => {
      queryClient.setQueryData(analysisTaskQueryKey(taskId), task)
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: analysisTaskQueryKey(taskId),
        }),
        queryClient.invalidateQueries({ queryKey: ['analyses'] }),
        queryClient.invalidateQueries({ queryKey: analysisSummaryQueryKey }),
        queryClient.invalidateQueries({
          queryKey: analysisReportQueryKey(taskId),
        }),
      ])
    },
    onSettled: () => {
      retryControllerRef.current = null
      retryInFlightRef.current = false
    },
  })

  useEffect(() => {
    return () => {
      retryControllerRef.current?.abort()
      retryControllerRef.current = null
      retryInFlightRef.current = false
    }
  }, [])

  const retryTask = () => {
    if (retryInFlightRef.current) {
      return
    }

    retryInFlightRef.current = true
    retryMutation.mutate()
  }

  if (taskQuery.isPending) {
    return (
      <AsyncState
        className="analysis-detail__page-state"
        label="正在加载分析详情"
        state="loading"
      />
    )
  }

  if (taskQuery.data === undefined) {
    const requestId = requestIdFrom(taskQuery.error)
    return (
      <div className="analysis-detail__page-state analysis-detail__load-error">
        <AsyncState
          description="请检查服务连接后重试。"
          onRetry={() => void taskQuery.refetch()}
          state="error"
          title="分析详情加载失败"
        />
        {requestId !== undefined ? (
          <CopyReference label="请求 ID" value={requestId} />
        ) : null}
      </div>
    )
  }

  const task = taskQuery.data
  return (
    <>
      {taskQuery.isError ? (
        <TaskConnectionBanner
          error={taskQuery.error}
          isRefreshing={taskQuery.isFetching}
          onRefresh={() => void taskQuery.refetch()}
        />
      ) : null}
      <TaskStatusSection
        onRetry={retryTask}
        retryError={retryMutation.error}
        retryPending={retryMutation.isPending}
        task={task}
      />
      <TaskMetadata task={task} />
      {task.status === 'SUCCESS' ? (
        <AnalysisReportSection taskId={taskId} />
      ) : null}
    </>
  )
}

export function AnalysisDetailPage() {
  const { taskId: rawTaskId } = useParams()
  const taskId = parseTaskId(rawTaskId)

  return (
    <section
      aria-labelledby="analysis-detail-heading"
      className="analysis-detail"
    >
      <AnalysisDetailHeader />
      {taskId === undefined ? (
        <div className="analysis-detail__page-state analysis-detail__invalid">
          <AsyncState
            description="请从分析历史中选择一条有效记录。"
            state="empty"
            title="任务 ID 无效"
          />
        </div>
      ) : (
        <ValidAnalysisDetail key={taskId} taskId={taskId} />
      )}
    </section>
  )
}

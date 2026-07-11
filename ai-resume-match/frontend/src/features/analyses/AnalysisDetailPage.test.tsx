import type { QueryClient } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterEach, expect, test, vi } from 'vitest'
import { createMemoryRouter } from 'react-router'

import { App } from '../../app/App'
import { createQueryClient } from '../../app/queryClient'
import { appRoutes, type AppRouter } from '../../app/router'
import type {
  AnalysisStatus,
  AnalysisTask,
  MatchReport as MatchReportData,
} from '../../shared/api/schemas'
import { server } from '../../test/server'
import { analysisSummaryQueryKey } from '../dashboard/useAnalysisSummaryQuery'
import { analysisTaskQueryKey } from './useAnalysisTask'
import { analysisReportQueryKey } from '../reports/reportQuery'

const TASK_ID = 42
const LOCAL_TIMESTAMP = '2026-07-10T09:00:00'
const reportResponse: MatchReportData = {
  taskId: TASK_ID,
  matchScore: 88,
  reportContent: '# 匹配报告\n\n- Java\n- Redis',
  createdAt: '2026-07-10T09:05:00',
}

function createTask(
  status: AnalysisStatus,
  overrides: Partial<AnalysisTask> = {},
): AnalysisTask {
  const failed = status.startsWith('FAILED')
  const completed = !['PENDING', 'RUNNING'].includes(status)

  return {
    taskId: TASK_ID,
    resumeId: 10,
    jobDescriptionId: 20,
    jobTitle: '高级后端工程师',
    resumeFileName: 'synthetic-resume.pdf',
    matchScore: status === 'SUCCESS' ? 88 : null,
    status,
    attemptCount: status === 'PENDING' ? 0 : 2,
    maxAttempts: 3,
    failureCode: failed ? 'AI_UNAVAILABLE' : null,
    failureMessage: failed ? 'SECRET_BACKEND_FAILURE_DETAIL' : null,
    nextRetryAt:
      status === 'FAILED_RETRYABLE' ? '2026-07-10T09:10:00' : null,
    startedAt: status === 'PENDING' ? null : '2026-07-10T09:01:00',
    completedAt: completed ? '2026-07-10T09:05:00' : null,
    createdAt: LOCAL_TIMESTAMP,
    updatedAt: '2026-07-10T09:05:00',
    ...overrides,
  }
}

function createDetailQueryClient() {
  const queryClient = createQueryClient()
  const defaults = queryClient.getDefaultOptions()
  queryClient.setDefaultOptions({
    ...defaults,
    queries: {
      ...defaults.queries,
      refetchOnWindowFocus: false,
      retry: false,
    },
  })
  return queryClient
}

interface RenderedDetail {
  dispose: () => void
  queryClient: QueryClient
  router: AppRouter
}

const renderedDetails: RenderedDetail[] = []

function renderDetail(
  initialEntry = `/analyses/${TASK_ID}`,
  queryClient = createDetailQueryClient(),
) {
  server.use(
    http.get('/backend-health', () => HttpResponse.json({ status: 'UP' })),
  )
  const router: AppRouter = createMemoryRouter(appRoutes, {
    initialEntries: [initialEntry],
  })
  const rendered = render(<App queryClient={queryClient} router={router} />)
  let disposed = false
  const app: RenderedDetail = {
    queryClient,
    router,
    dispose: () => {
      if (disposed) {
        return
      }
      disposed = true
      rendered.unmount()
      router.dispose()
      queryClient.clear()
    },
  }
  renderedDetails.push(app)
  return app
}

function metadataValue(label: string) {
  const term = screen.getByText(label, { selector: 'dt' })
  const value = term.parentElement?.querySelector('dd')
  expect(value).not.toBeNull()
  return value!
}

function replaceClipboard(clipboard: Pick<Clipboard, 'writeText'> | undefined) {
  const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: clipboard,
  })

  return () => {
    if (descriptor === undefined) {
      Reflect.deleteProperty(navigator, 'clipboard')
    } else {
      Object.defineProperty(navigator, 'clipboard', descriptor)
    }
  }
}

afterEach(() => {
  for (const app of renderedDetails.splice(0)) {
    app.dispose()
  }
  vi.restoreAllMocks()
})

test.each([
  '0',
  '-1',
  '01',
  '+1',
  '1.0',
  '1e3',
  '9007199254740992',
  '9223372036854775807',
  'not-a-task',
])('rejects non-canonical or unsafe task id %s without requesting it', async (rawId) => {
  let taskRequestCount = 0
  server.use(
    http.get('/api/analysis/:taskId', () => {
      taskRequestCount += 1
      return HttpResponse.json(createTask('PENDING'))
    }),
  )

  renderDetail(`/analyses/${rawId}`)

  expect(await screen.findByText('任务 ID 无效')).toBeVisible()
  expect(screen.getByRole('link', { name: '返回分析历史' })).toHaveAttribute(
    'href',
    '/analyses',
  )
  expect(taskRequestCount).toBe(0)
})

test('accepts the maximum canonical JavaScript-safe positive task id', async () => {
  const maximumSafeTaskId = Number.MAX_SAFE_INTEGER
  let observedTaskId: string | undefined
  server.use(
    http.get('/api/analysis/:taskId', ({ params }) => {
      observedTaskId = String(params.taskId)
      return HttpResponse.json(
        createTask('FAILED_FINAL', { taskId: maximumSafeTaskId }),
      )
    }),
  )

  renderDetail(`/analyses/${maximumSafeTaskId}`)

  expect(await screen.findByText('最终失败')).toBeVisible()
  expect(observedTaskId).toBe(String(maximumSafeTaskId))
})

test('shows compact task metadata with stable nullable and local date formatting', async () => {
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(
        createTask('FAILED_RETRYABLE', {
          jobTitle: null,
          resumeFileName: null,
        }),
      ),
    ),
  )

  renderDetail()

  expect(await screen.findByText('可重试失败')).toBeVisible()
  expect(metadataValue('岗位')).toHaveTextContent('—')
  expect(metadataValue('简历文件')).toHaveTextContent('—')
  expect(metadataValue('任务 ID')).toHaveTextContent(String(TASK_ID))
  expect(metadataValue('状态')).toHaveTextContent('可重试失败')
  expect(metadataValue('尝试次数')).toHaveTextContent('2 / 3')
  expect(metadataValue('创建时间')).toHaveTextContent('2026-07-10 09:00')
  expect(metadataValue('开始时间')).toHaveTextContent('2026-07-10 09:01')
  expect(metadataValue('完成时间')).toHaveTextContent('2026-07-10 09:05')
  expect(metadataValue('下次重试')).toHaveTextContent('2026-07-10 09:10')
})

test('announces business status changes in one stable live region and keeps transport errors in an alert', async () => {
  let requestCount = 0
  let releaseInitialTask: () => void = () => undefined
  const initialTaskGate = new Promise<void>((resolve) => {
    releaseInitialTask = resolve
  })
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, async () => {
      requestCount += 1
      if (requestCount === 1) {
        await initialTaskGate
        return HttpResponse.json(createTask('PENDING'))
      }
      if (requestCount === 2) {
        return HttpResponse.json(createTask('RUNNING'))
      }
      return HttpResponse.error()
    }),
  )
  const { queryClient } = renderDetail()

  try {
    const statusRegion = screen.getByRole('status', {
      name: '任务状态更新',
    })
    expect(statusRegion).toHaveAttribute('aria-live', 'polite')
    expect(statusRegion).toHaveAttribute('aria-atomic', 'true')
    expect(statusRegion).toBeEmptyDOMElement()

    releaseInitialTask()
    await waitFor(() => {
      expect(statusRegion).toHaveTextContent('任务状态：待处理。')
    })

    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: analysisTaskQueryKey(TASK_ID),
        exact: true,
      })
    })

    expect(
      screen.getByRole('status', { name: '任务状态更新' }),
    ).toBe(statusRegion)
    await waitFor(() => {
      expect(statusRegion).toHaveTextContent('任务状态：分析中。')
    })

    await act(async () => {
      await queryClient.refetchQueries({
        queryKey: analysisTaskQueryKey(TASK_ID),
        exact: true,
      })
    })

    const transportAlert = await screen.findByRole('alert')
    expect(transportAlert).toHaveTextContent(
      '连接中断，当前显示上次获取的任务状态。',
    )
    expect(
      screen.getByRole('status', { name: '任务状态更新' }),
    ).toBe(statusRegion)
    expect(statusRegion).toHaveTextContent('任务状态：分析中。')
    expect(statusRegion).not.toHaveTextContent('连接中断')
  } finally {
    releaseInitialTask()
  }
})

test('keeps the last-known business status after a network failure and manually refreshes it', async () => {
  let requestCount = 0
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () => {
      requestCount += 1
      if (requestCount === 1) {
        return HttpResponse.json(createTask('PENDING'))
      }
      if (requestCount === 2) {
        return HttpResponse.error()
      }
      return HttpResponse.json(createTask('RUNNING'))
    }),
  )
  const { queryClient } = renderDetail()

  expect(await screen.findByText('待处理')).toBeVisible()

  await act(async () => {
    await queryClient.refetchQueries({
      queryKey: analysisTaskQueryKey(TASK_ID),
    })
  })

  expect(
    await screen.findByText('连接中断，当前显示上次获取的任务状态。'),
  ).toBeVisible()
  expect(screen.getByText('待处理')).toBeVisible()
  expect(screen.queryByText('可重试失败')).not.toBeInTheDocument()

  await userEvent.setup().click(
    screen.getByRole('button', { name: '刷新任务状态' }),
  )

  expect(await screen.findByText('分析中')).toBeVisible()
  expect(
    screen.queryByText('连接中断，当前显示上次获取的任务状态。'),
  ).not.toBeInTheDocument()
  expect(requestCount).toBe(3)
})

test('shows an initial task error with an accurate request id and recovers on retry', async () => {
  const privateServerMessage = 'SECRET_PRIVATE_SERVER_MESSAGE'
  let requestCount = 0
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () => {
      requestCount += 1
      return requestCount === 1
        ? HttpResponse.json(
            {
              code: 'TASK_LOOKUP_FAILED',
              message: privateServerMessage,
              requestId: 'req-load-42',
            },
            { status: 503 },
          )
        : HttpResponse.json(createTask('PENDING'))
    }),
  )
  const user = userEvent.setup()

  renderDetail()

  expect(await screen.findByText('分析详情加载失败')).toBeVisible()
  expect(screen.queryByText(privateServerMessage)).not.toBeInTheDocument()
  expect(screen.getByText('req-load-42')).toBeVisible()
  expect(screen.queryByText('任务 ID', { selector: 'dt' })).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '复制请求 ID' }))
  expect(await navigator.clipboard.readText()).toBe('req-load-42')
  expect(await screen.findByText('请求 ID 已复制')).toBeVisible()

  await user.click(screen.getByRole('button', { name: '重试' }))
  expect(await screen.findByText('待处理')).toBeVisible()
  expect(requestCount).toBe(2)
})

test.each([
  ['FAILED_FINAL', '最终失败', '分析服务暂时不可用。'],
  ['CANCELLED', '已取消', '任务已取消。'],
  ['FAILED', '分析失败', '任务未能完成。'],
] as const)(
  '%s shows a safe terminal summary and copyable task id without retry',
  async (status, statusLabel, summary) => {
    server.use(
      http.get(`/api/analysis/${TASK_ID}`, () =>
        HttpResponse.json(createTask(status)),
      ),
    )
    const user = userEvent.setup()

    renderDetail()

    expect(await screen.findByText(statusLabel)).toBeVisible()
    expect(screen.getByText(summary)).toBeVisible()
    expect(screen.queryByText('SECRET_BACKEND_FAILURE_DETAIL')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '重新分析' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('请求 ID')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '复制任务 ID' }))
    expect(await navigator.clipboard.readText()).toBe(String(TASK_ID))
    expect(await screen.findByText('任务 ID 已复制')).toBeVisible()
  },
)

test('retries only once, updates the task cache immediately and invalidates related queries', async () => {
  let retried = false
  let retryRequestCount = 0
  let markRetryStarted: () => void = () => undefined
  const retryStarted = new Promise<void>((resolve) => {
    markRetryStarted = resolve
  })
  let releaseRetry: () => void = () => undefined
  const retryGate = new Promise<void>((resolve) => {
    releaseRetry = resolve
  })
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask(retried ? 'PENDING' : 'FAILED_RETRYABLE')),
    ),
    http.post(`/api/analysis/${TASK_ID}/retry`, async () => {
      retryRequestCount += 1
      markRetryStarted()
      await retryGate
      retried = true
      return HttpResponse.json(createTask('PENDING'))
    }),
  )
  const queryClient = createDetailQueryClient()
  queryClient.setQueryData(analysisReportQueryKey(TASK_ID), reportResponse)
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

  renderDetail(`/analyses/${TASK_ID}`, queryClient)

  const retryButton = await screen.findByRole('button', {
    name: '重新分析',
  })
  act(() => {
    retryButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    retryButton.dispatchEvent(new MouseEvent('click', { bubbles: true }))
  })
  await retryStarted

  expect(retryRequestCount).toBe(1)
  await waitFor(() => {
    expect(retryButton).toBeDisabled()
    expect(retryButton).toHaveAttribute('aria-busy', 'true')
  })

  releaseRetry()

  expect(await screen.findByText('待处理')).toBeVisible()
  expect(
    queryClient.getQueryData<AnalysisTask>(analysisTaskQueryKey(TASK_ID))
      ?.status,
  ).toBe('PENDING')
  await waitFor(() => {
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: analysisTaskQueryKey(TASK_ID),
      exact: true,
    })
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['analyses'] })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: analysisSummaryQueryKey,
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: analysisReportQueryKey(TASK_ID),
    })
  })
  expect(invalidateSpy).toHaveBeenCalledTimes(4)
  expect(retryRequestCount).toBe(1)
})

test('preserves enhanced metadata from a null retry DTO when the task refresh fails', async () => {
  const initialTask = createTask('FAILED_RETRYABLE', {
    jobTitle: '增强岗位元数据',
    resumeFileName: 'enhanced-resume.pdf',
    matchScore: 73,
  })
  const retryResponse = createTask('PENDING', {
    jobTitle: null,
    resumeFileName: null,
    matchScore: null,
    attemptCount: 3,
    maxAttempts: 4,
    updatedAt: '2026-07-10T09:06:00',
  })
  let taskRequestCount = 0
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () => {
      taskRequestCount += 1
      return taskRequestCount === 1
        ? HttpResponse.json(initialTask)
        : HttpResponse.error()
    }),
    http.post(`/api/analysis/${TASK_ID}/retry`, () =>
      HttpResponse.json(retryResponse),
    ),
  )
  const { queryClient } = renderDetail()
  const user = userEvent.setup()

  await user.click(
    await screen.findByRole('button', { name: '重新分析' }),
  )

  expect(await screen.findByText('待处理')).toBeVisible()
  expect(
    await screen.findByText('连接中断，当前显示上次获取的任务状态。'),
  ).toBeVisible()
  expect(metadataValue('岗位')).toHaveTextContent('增强岗位元数据')
  expect(metadataValue('简历文件')).toHaveTextContent('enhanced-resume.pdf')

  const cachedTask = queryClient.getQueryData<AnalysisTask>(
    analysisTaskQueryKey(TASK_ID),
  )
  expect(cachedTask).toMatchObject({
    status: 'PENDING',
    attemptCount: 3,
    maxAttempts: 4,
    failureCode: null,
    failureMessage: null,
    nextRetryAt: null,
    startedAt: null,
    completedAt: null,
    updatedAt: '2026-07-10T09:06:00',
    jobTitle: '增强岗位元数据',
    resumeFileName: 'enhanced-resume.pdf',
    matchScore: 73,
  })
  expect(taskRequestCount).toBe(2)
})

test('resyncs the exact task when the retry response is lost after the server accepts it', async () => {
  let serverStatus: AnalysisStatus = 'FAILED_RETRYABLE'
  let taskRequestCount = 0
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () => {
      taskRequestCount += 1
      return HttpResponse.json(createTask(serverStatus))
    }),
    http.post(`/api/analysis/${TASK_ID}/retry`, () => {
      serverStatus = 'PENDING'
      return HttpResponse.error()
    }),
  )
  const queryClient = createDetailQueryClient()
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
  renderDetail(`/analyses/${TASK_ID}`, queryClient)
  const user = userEvent.setup()

  await user.click(
    await screen.findByRole('button', { name: '重新分析' }),
  )

  expect(await screen.findByText('待处理')).toBeVisible()
  expect(
    screen.queryByRole('button', { name: '重新分析' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByText('重新分析失败，请稍后重试。'),
  ).not.toBeInTheDocument()
  expect(taskRequestCount).toBe(2)
  expect(invalidateSpy).toHaveBeenCalledWith({
    queryKey: analysisTaskQueryKey(TASK_ID),
    exact: true,
  })
  expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['analyses'] })
  expect(invalidateSpy).toHaveBeenCalledWith({
    queryKey: analysisSummaryQueryKey,
  })
  expect(invalidateSpy).toHaveBeenCalledWith({
    queryKey: analysisReportQueryKey(TASK_ID),
  })
  expect(invalidateSpy).toHaveBeenCalledTimes(4)
})

test('shows only a safe retry error and copies its transport request id', async () => {
  const privateServerMessage = 'SECRET_RETRY_RESPONSE_DETAIL'
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('FAILED_RETRYABLE')),
    ),
    http.post(`/api/analysis/${TASK_ID}/retry`, () =>
      HttpResponse.json(
        {
          code: 'RETRY_UNAVAILABLE',
          message: privateServerMessage,
          requestId: 'req-retry-42',
        },
        { status: 503 },
      ),
    ),
  )
  const user = userEvent.setup()

  renderDetail()

  await user.click(
    await screen.findByRole('button', { name: '重新分析' }),
  )

  expect(await screen.findByText('重新分析失败，请稍后重试。')).toBeVisible()
  expect(screen.queryByText(privateServerMessage)).not.toBeInTheDocument()
  expect(screen.getByText('req-retry-42')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '复制请求 ID' }))
  expect(await navigator.clipboard.readText()).toBe('req-retry-42')
})

test('does not request a report before the task succeeds', async () => {
  let reportRequestCount = 0
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('PENDING')),
    ),
    http.get(`/api/analysis/${TASK_ID}/report`, () => {
      reportRequestCount += 1
      return HttpResponse.json(reportResponse)
    }),
  )

  renderDetail()

  expect(await screen.findByText('待处理')).toBeVisible()
  expect(reportRequestCount).toBe(0)
  expect(screen.queryByRole('region', { name: '匹配报告' })).not.toBeInTheDocument()
})

test('keeps report loading and errors independent, then manually retries with a stable score', async () => {
  let reportRequestCount = 0
  let markReportStarted: () => void = () => undefined
  const reportStarted = new Promise<void>((resolve) => {
    markReportStarted = resolve
  })
  let releaseFirstReport: () => void = () => undefined
  const firstReportGate = new Promise<void>((resolve) => {
    releaseFirstReport = resolve
  })
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('SUCCESS')),
    ),
    http.get(`/api/analysis/${TASK_ID}/report`, async () => {
      reportRequestCount += 1
      if (reportRequestCount === 1) {
        markReportStarted()
        await firstReportGate
        return HttpResponse.json(
          {
            code: 'REPORT_UNAVAILABLE',
            message: 'SECRET_REPORT_TRANSPORT_DETAIL',
            requestId: 'req-report-42',
          },
          { status: 503 },
        )
      }
      return HttpResponse.json(reportResponse)
    }),
  )
  const user = userEvent.setup()

  renderDetail()
  await reportStarted

  expect(screen.getByText('已完成')).toBeVisible()
  const reportRegion = screen.getByRole('region', { name: '匹配报告' })
  expect(within(reportRegion).getByText('正在加载匹配报告')).toBeVisible()

  releaseFirstReport()

  expect(
    await within(reportRegion).findByText('匹配报告加载失败'),
  ).toBeVisible()
  expect(screen.getByText('已完成')).toBeVisible()
  expect(screen.queryByText('SECRET_REPORT_TRANSPORT_DETAIL')).not.toBeInTheDocument()
  expect(within(reportRegion).getByText('req-report-42')).toBeVisible()

  await user.click(within(reportRegion).getByRole('button', { name: '重试' }))

  expect(
    await within(reportRegion).findByRole('heading', { name: '匹配报告' }),
  ).toBeVisible()
  expect(within(reportRegion).getByText('88.0 / 100')).toBeVisible()
  expect(reportRequestCount).toBe(2)
  expect(screen.getByRole('button', { name: '复制任务 ID' })).toBeVisible()
  expect(screen.queryByRole('button', { name: '重新分析' })).not.toBeInTheDocument()
})

test('aborts an in-flight report request when the detail page unmounts', async () => {
  let reportRequestAborted = false
  let markReportStarted: () => void = () => undefined
  const reportStarted = new Promise<void>((resolve) => {
    markReportStarted = resolve
  })
  let releaseReport: () => void = () => undefined
  const reportRelease = new Promise<void>((resolve) => {
    releaseReport = resolve
  })
  let markReportFinished: () => void = () => undefined
  const reportFinished = new Promise<void>((resolve) => {
    markReportFinished = resolve
  })
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('SUCCESS')),
    ),
    http.get(`/api/analysis/${TASK_ID}/report`, async ({ request }) => {
      markReportStarted()
      try {
        await Promise.race([
          reportRelease,
          new Promise<void>((resolve) => {
            request.signal.addEventListener(
              'abort',
              () => {
                reportRequestAborted = true
                resolve()
              },
              { once: true },
            )
          }),
        ])
        return HttpResponse.json(reportResponse)
      } finally {
        markReportFinished()
      }
    }),
  )

  const app = renderDetail()

  try {
    await reportStarted
    app.dispose()
    await waitFor(() => {
      expect(reportRequestAborted).toBe(true)
    })
  } finally {
    releaseReport()
    await reportFinished
  }
})

test('announces safely when the Clipboard API is unavailable', async () => {
  const restoreClipboard = replaceClipboard(undefined)
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('FAILED_FINAL')),
    ),
  )

  try {
    renderDetail()
    const copyButton = await screen.findByRole('button', {
      name: '复制任务 ID',
    })

    fireEvent.click(copyButton)

    expect(await screen.findByText('当前环境无法复制任务 ID')).toBeVisible()
  } finally {
    restoreClipboard()
  }
})

test('announces a safe clipboard failure without exposing the rejection', async () => {
  const privateClipboardError = 'SECRET_CLIPBOARD_REJECTION'
  const restoreClipboard = replaceClipboard({
    writeText: vi.fn().mockRejectedValue(new Error(privateClipboardError)),
  })
  server.use(
    http.get(`/api/analysis/${TASK_ID}`, () =>
      HttpResponse.json(createTask('FAILED_FINAL')),
    ),
  )

  try {
    renderDetail()
    fireEvent.click(
      await screen.findByRole('button', { name: '复制任务 ID' }),
    )

    expect(await screen.findByText('复制任务 ID 失败')).toBeVisible()
    expect(screen.queryByText(privateClipboardError)).not.toBeInTheDocument()
  } finally {
    restoreClipboard()
  }
})

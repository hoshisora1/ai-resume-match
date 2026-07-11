import type { Page, Route } from '@playwright/test'

export type MockTaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'FAILED_RETRYABLE'
  | 'FAILED_FINAL'

const createdAt = '2026-07-10T09:00:00'
const updatedAt = '2026-07-10T09:05:00'

interface MockApiState {
  failFinalTaskRefresh: boolean
  retrySubmitted: boolean
  submissionBody: string
  submissionContentType: string
  submitted: boolean
  task42FetchCount: number
  task42Sequence: MockTaskStatus[]
}

export interface MockApiControls {
  failFinalTaskRefresh(): void
  readonly state: Readonly<MockApiState>
  setTask42Sequence(statuses: MockTaskStatus[]): void
}

function task(
  taskId: number,
  status: MockTaskStatus,
  overrides: Record<string, unknown> = {},
) {
  const failed = status === 'FAILED_RETRYABLE' || status === 'FAILED_FINAL'
  const active = status === 'PENDING' || status === 'RUNNING'

  return {
    taskId,
    resumeId: taskId + 100,
    jobDescriptionId: taskId + 200,
    jobTitle:
      taskId === 42 ? '高级 Java AI 应用工程师' : '平台工程师',
    resumeFileName: taskId === 42 ? 'resume.pdf' : 'candidate.pdf',
    matchScore: status === 'SUCCESS' ? 88 : null,
    status,
    attemptCount: status === 'PENDING' ? 0 : 1,
    maxAttempts: 3,
    failureCode: failed ? 'AI_PROVIDER_UNAVAILABLE' : null,
    failureMessage: failed ? '分析服务暂时不可用' : null,
    nextRetryAt:
      status === 'FAILED_RETRYABLE' ? '2026-07-10T09:10:00' : null,
    startedAt: status === 'PENDING' ? null : '2026-07-10T09:01:00',
    completedAt: active ? null : updatedAt,
    createdAt,
    updatedAt,
    ...overrides,
  }
}

function listItem(taskId: number, status: MockTaskStatus) {
  const value = task(taskId, status)
  return {
    taskId: value.taskId,
    jobTitle: value.jobTitle,
    resumeFileName: value.resumeFileName,
    status: value.status,
    matchScore: value.matchScore,
    attemptCount: value.attemptCount,
    maxAttempts: value.maxAttempts,
    failureCode: value.failureCode,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
    completedAt: value.completedAt,
  }
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ json: body, status })
}

function pageResponse(items: unknown[], requestedSize: number) {
  return {
    items,
    page: 0,
    size: requestedSize,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : 1,
  }
}

function currentTask42Status(state: MockApiState) {
  const index = Math.min(
    state.task42FetchCount,
    state.task42Sequence.length - 1,
  )
  const status = state.task42Sequence[index] ?? 'SUCCESS'
  state.task42FetchCount += 1
  return status
}

export async function installMockApi(page: Page): Promise<MockApiControls> {
  const state: MockApiState = {
    failFinalTaskRefresh: false,
    retrySubmitted: false,
    submissionBody: '',
    submissionContentType: '',
    submitted: false,
    task42FetchCount: 0,
    task42Sequence: ['PENDING', 'RUNNING', 'SUCCESS'],
  }

  await page.route('**/backend-health', (route) =>
    json(route, { status: 'UP' }),
  )

  await page.route(/^https?:\/\/[^/]+\/api(?:\/|$)/, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'GET' && url.pathname === '/api/analysis/summary') {
      return json(route, {
        totalCount: state.submitted ? 2 : 1,
        successCount: state.submitted ? 2 : 1,
        inProgressCount: 0,
        retryableFailureCount: 0,
        averageMatchScore: state.submitted ? 86 : 84,
      })
    }

    if (method === 'POST' && url.pathname === '/api/analysis-submissions') {
      state.submissionContentType = request.headers()['content-type'] ?? ''
      state.submissionBody = request.postDataBuffer()?.toString('utf8') ?? ''
      state.submitted = true
      state.task42FetchCount = 0
      return json(route, task(42, 'PENDING'))
    }

    const reportMatch = url.pathname.match(/^\/api\/analysis\/(\d+)\/report$/)
    if (method === 'GET' && reportMatch !== null) {
      return json(route, {
        taskId: Number(reportMatch[1]),
        matchScore: 88,
        reportContent:
          '# 匹配报告\n\n## 核心结论\n候选人的 Java、Spring Boot 与消息队列经验匹配岗位要求。',
        createdAt: updatedAt,
      })
    }

    const retryMatch = url.pathname.match(/^\/api\/analysis\/(\d+)\/retry$/)
    if (method === 'POST' && retryMatch !== null) {
      const taskId = Number(retryMatch[1])
      state.retrySubmitted = true
      return json(
        route,
        task(taskId, 'PENDING', {
          attemptCount: 1,
          nextRetryAt: null,
        }),
      )
    }

    const taskMatch = url.pathname.match(/^\/api\/analysis\/(\d+)$/)
    if (method === 'GET' && taskMatch !== null) {
      const taskId = Number(taskMatch[1])
      if (taskId === 42) {
        return json(route, task(42, currentTask42Status(state)))
      }
      if (taskId === 43) {
        return json(
          route,
          task(43, state.retrySubmitted ? 'PENDING' : 'FAILED_RETRYABLE'),
        )
      }
      if (taskId === 44 && state.failFinalTaskRefresh) {
        return json(
          route,
          {
            code: 'TASK_REFRESH_FAILED',
            message: 'Task refresh failed',
            requestId: 'req-final-44',
          },
          503,
        )
      }
      if (taskId === 44) {
        return json(route, task(44, 'FAILED_FINAL'))
      }
    }

    if (method === 'GET' && url.pathname === '/api/analysis') {
      const requestedSize = Number(url.searchParams.get('size') ?? '20')
      const task42Status =
        state.task42Sequence[
          Math.min(
            Math.max(0, state.task42FetchCount - 1),
            state.task42Sequence.length - 1,
          )
        ] ?? 'SUCCESS'
      const items = [
        ...(state.submitted ? [listItem(42, task42Status)] : []),
        {
          ...listItem(1, 'SUCCESS'),
          jobTitle: '后端平台工程师',
          resumeFileName: 'existing.pdf',
          matchScore: 84,
        },
      ]
      const status = url.searchParams.get('status')
      const filtered = status
        ? items.filter((item) => item.status === status)
        : items
      return json(route, pageResponse(filtered.slice(0, requestedSize), requestedSize))
    }

    return json(
      route,
      {
        code: 'MOCK_ROUTE_NOT_FOUND',
        message: `${method} ${url.pathname} is not mocked`,
        requestId: 'req-unmatched-mock',
      },
      501,
    )
  })

  return {
    failFinalTaskRefresh() {
      state.failFinalTaskRefresh = true
    },
    state,
    setTask42Sequence(statuses) {
      state.task42Sequence = statuses.length === 0 ? ['SUCCESS'] : [...statuses]
      state.task42FetchCount = 0
    },
  }
}

export function trackExternalRequests(page: Page) {
  const requests: string[] = []
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (
      (url.protocol === 'http:' || url.protocol === 'https:') &&
      url.hostname !== '127.0.0.1' &&
      url.hostname !== 'localhost'
    ) {
      requests.push(request.url())
    }
  })
  return requests
}

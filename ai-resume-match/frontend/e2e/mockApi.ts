import type { Page, Route } from '@playwright/test'

import {
  DEMO_JOB_TITLE,
  DEMO_RESUME_FILE_NAME,
} from '../src/features/analyses/demoAnalysisFixture'

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
    jobTitle: taskId === 42 ? DEMO_JOB_TITLE : '平台工程师',
    resumeFileName:
      taskId === 42 ? DEMO_RESUME_FILE_NAME : 'candidate.pdf',
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

function json(
  route: Route,
  body: unknown,
  status = 200,
  headers?: Record<string, string>,
) {
  return route.fulfill({ headers, json: body, status })
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
      return json(route, task(42, 'PENDING'), 202, {
        Location: '/api/analysis/42',
      })
    }

    const reportMatch = url.pathname.match(/^\/api\/analysis\/(\d+)\/report$/)
    if (method === 'GET' && reportMatch !== null) {
      return json(route, {
        taskId: Number(reportMatch[1]),
        matchScore: 88,
        reportSchemaVersion: 'match-report-v2',
        structuredReport: {
          schemaVersion: 'match-report-v2',
          matchScore: 88,
          requirements: [
            {
              requirementId: 'requirement:0',
              text: 'Java 与 Spring Boot 工程经验',
              mustHave: true,
              weight: 3,
              modelStatus: 'supported',
              status: 'supported',
              explanation: '简历片段覆盖 Java 与 Spring Boot 项目经验。',
              evidenceIds: ['resume:0'],
              verification: {
                verifierVersion: 'conservative-lexical-negation-v1',
                status: 'supported',
                termCoverage: 1,
                reason: 'required_terms_supported',
                evidenceIds: ['resume:0'],
              },
            },
            {
              requirementId: 'requirement:1',
              text: 'AI 应用工程化',
              mustHave: false,
              weight: 1,
              modelStatus: 'partial',
              status: 'partial',
              explanation: '检索到 Agent 工具调用经验，但缺少线上效果指标。',
              evidenceIds: ['resume:1'],
              verification: {
                verifierVersion: 'conservative-lexical-negation-v1',
                status: 'partial',
                termCoverage: 0.5,
                reason: 'partial_term_support',
                evidenceIds: ['resume:1'],
              },
            },
          ],
          coreClaims: [
            {
              claim: '具备 Java 与 Spring Boot 后端项目经验。',
              evidenceIds: ['resume:0'],
            },
          ],
          matchedSkills: [
            {
              claim: '具备受限 Tool Calling Agent 开发经验。',
              evidenceIds: ['resume:1'],
            },
          ],
          skillGaps: ['尚未提供线上模型质量与成本指标。'],
          recommendations: ['补充真实评测结果', '展示检索对比', '记录端到端成本'],
          interviewQuestions: ['如何防止伪造引用？', '如何控制 Agent 预算？', '如何评测检索质量？'],
          evidence: [
            {
              evidenceId: 'resume:0',
              excerpt: '使用 Java、Spring Boot 与 RabbitMQ 构建异步分析服务。',
              score: 0.94,
              sourceStart: 12,
              sourceEnd: 53,
            },
            {
              evidenceId: 'resume:1',
              excerpt: '实现三个白名单工具和逐项证据引用校验。',
              score: 0.82,
              sourceStart: 54,
              sourceEnd: 76,
            },
          ],
          scoreBreakdown: {
            rawScore: 88,
            finalScore: 88,
            totalWeight: 4,
            supportedWeight: 3,
            partialWeight: 1,
            missingWeight: 0,
            mustHaveCapApplied: false,
          },
        },
        provenance: {
          schemaVersion: 'analysis-run-v1',
          correlationId: 'browser-e2e-42',
          model: 'deterministic-e2e-model',
          promptVersion: 'requirement-verified-agent-v3',
          retrieverVersion: 'hashing-256-v1',
          verifierVersion: 'conservative-lexical-negation-v1',
          steps: 4,
          runMetadata: {
            schemaVersion: 'agent-run-v1',
            requestSchemaVersion: 'agent-analysis-request-v1',
            agentRuntimeVersion: 'bounded-tool-agent-v1',
            inputFingerprintVersion: 'sha256-task-scoped-length-prefixed-v1',
            inputFingerprint:
              '9c4fd485fd3f2d1af7f1d7174eeb0c68aa812d309277f4e05d49af178ad6c935',
            chatProviderCalls: 4,
            chatProviderDurationMs: 840,
            toolDurationMs: 4,
            totalDurationMs: 1_120,
            contextCharsSent: 9_842,
          },
          modelUsage: {
            promptTokens: 240,
            completionTokens: 90,
            totalTokens: 330,
            providerReported: true,
          },
          toolTrace: [
            { name: 'get_job_requirements', outcome: 'success', durationMs: 1 },
            { name: 'search_resume_evidence', outcome: 'success', durationMs: 2 },
            { name: 'submit_match_report', outcome: 'success', durationMs: 1 },
          ],
        },
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

import { HttpResponse, http } from 'msw'
import { describe, expect, test, vi } from 'vitest'

import { server } from '../../test/server'
import {
  createAnalysisSubmission,
  getAnalysisSummary,
  getAnalysisTask,
  getBackendHealth,
  getMatchReport,
  listAnalyses,
  retryAnalysisTask,
} from './analyses'
import { ApiError, apiRequest } from './client'
import { analysisStatusSchema, healthSchema } from './schemas'

const localTimestamp = '2026-07-10T09:00:00'

const pendingTaskResponse = {
  taskId: 30,
  resumeId: 10,
  jobDescriptionId: 20,
  jobTitle: null,
  resumeFileName: null,
  matchScore: null,
  status: 'PENDING',
  attemptCount: 0,
  maxAttempts: 3,
  failureCode: null,
  failureMessage: null,
  nextRetryAt: null,
  startedAt: null,
  completedAt: null,
  createdAt: localTimestamp,
  updatedAt: localTimestamp,
} as const

const analysisPageResponse = {
  items: [
    {
      taskId: 30,
      jobTitle: '高级后端工程师',
      resumeFileName: 'resume.pdf',
      status: 'SUCCESS',
      matchScore: 88,
      attemptCount: 1,
      maxAttempts: 3,
      failureCode: null,
      createdAt: localTimestamp,
      updatedAt: '2026-07-10T09:05:00',
      completedAt: '2026-07-10T09:05:00',
    },
  ],
  page: 2,
  size: 10,
  totalElements: 21,
  totalPages: 3,
} as const

describe('runtime schemas', () => {
  test.each([
    'PENDING',
    'RUNNING',
    'SUCCESS',
    'FAILED_RETRYABLE',
    'FAILED_FINAL',
    'CANCELLED',
    'FAILED',
  ])('accepts backend analysis status %s', (status) => {
    expect(analysisStatusSchema.parse(status)).toBe(status)
  })

  test('rejects an unknown analysis status', () => {
    expect(analysisStatusSchema.safeParse('UNKNOWN').success).toBe(false)
  })
})

describe('apiRequest', () => {
  test('validates a successful response and protects default headers for Headers and tuples', async () => {
    const observations: Array<{
      accept: string | null
      customHeader: string | null
      requestId: string | null
      token: string | null
    }> = []
    server.use(
      http.get('/api/header-probe', ({ request }) => {
        observations.push({
          accept: request.headers.get('Accept'),
          customHeader: request.headers.get('X-Custom-Header'),
          requestId: request.headers.get('X-Request-Id'),
          token: request.headers.get('X-API-Token'),
        })
        return HttpResponse.json({ status: 'UP' })
      }),
    )

    await apiRequest('/api/header-probe', healthSchema, {
      headers: new Headers({
        Accept: 'text/plain',
        'X-Custom-Header': 'headers-value',
        'X-Request-Id': 'caller-request-id',
      }),
    })
    const tupleHeaders: [string, string][] = [
      ['Accept', 'text/html'],
      ['X-Custom-Header', 'tuple-value'],
      ['X-Request-Id', 'another-caller-request-id'],
    ]
    await apiRequest('/api/header-probe', healthSchema, {
      headers: tupleHeaders,
    })

    expect(observations.map(({ accept }) => accept)).toEqual([
      'application/json',
      'application/json',
    ])
    expect(observations.map(({ customHeader }) => customHeader)).toEqual([
      'headers-value',
      'tuple-value',
    ])
    expect(observations.map(({ token }) => token)).toEqual([null, null])
    const requestIds = observations.map(({ requestId }) => requestId)
    expect(requestIds).not.toContain('caller-request-id')
    expect(requestIds).not.toContain('another-caller-request-id')
    expect(requestIds).toEqual([
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
    ])
    expect(new Set(requestIds).size).toBe(2)
  })

  test('throws a structured ApiError with the backend request id', async () => {
    server.use(
      http.get('/api/analysis/404', () =>
        HttpResponse.json(
          {
            code: 'NOT_FOUND',
            message: 'Analysis task not found',
            requestId: 'req-404',
          },
          { status: 404 },
        ),
      ),
    )

    const error = await getAnalysisTask(404).catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 'NOT_FOUND',
      message: 'Analysis task not found',
      requestId: 'req-404',
      status: 404,
    })
  })

  test('maps a non-structured HTTP failure and uses the response request id', async () => {
    server.use(
      http.get(
        '/api/analysis/502',
        () =>
          new HttpResponse('<html>Bad gateway</html>', {
            status: 502,
            headers: {
              'Content-Type': 'text/html',
              'X-Request-Id': 'req-gateway',
            },
          }),
      ),
    )

    await expect(getAnalysisTask(502)).rejects.toMatchObject({
      code: 'HTTP_ERROR',
      message: '请求失败',
      requestId: 'req-gateway',
      status: 502,
    })
  })

  test('rejects a successful response that violates the runtime schema', async () => {
    server.use(
      http.get('/api/analysis/1', () =>
        HttpResponse.json({ taskId: 'not-a-number' }),
      ),
    )

    await expect(getAnalysisTask(1)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      message: '服务返回了无法识别的数据',
      status: 200,
    })
  })

  test('rejects malformed JSON from a successful response', async () => {
    server.use(
      http.get(
        '/backend-health',
        () =>
          new HttpResponse('{"status":', {
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )

    await expect(getBackendHealth()).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      status: 200,
    })
  })

  test('rejects an empty successful response body', async () => {
    server.use(
      http.get(
        '/backend-health',
        () =>
          new HttpResponse(null, {
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )

    await expect(getBackendHealth()).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      status: 200,
    })
  })

  test('maps an ordinary fetch failure to a stable network error', async () => {
    server.use(
      http.get('/backend-health', () => HttpResponse.error()),
    )

    await expect(getBackendHealth()).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      message: '无法连接到服务',
      status: 0,
    })
  })

  test('preserves AbortError as cancellation instead of reporting a server error', async () => {
    const controller = new AbortController()
    controller.abort()

    const error = await apiRequest('/backend-health', healthSchema, {
      signal: controller.signal,
    }).catch((reason: unknown) => reason)

    expect(error).not.toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ name: 'AbortError' })
  })
})

describe('analysis endpoints', () => {
  test('gets the analysis summary including a nullable average score', async () => {
    const summary = {
      totalCount: 0,
      successCount: 0,
      inProgressCount: 0,
      retryableFailureCount: 0,
      averageMatchScore: null,
    }
    server.use(
      http.get('/api/analysis/summary', () => HttpResponse.json(summary)),
    )

    await expect(getAnalysisSummary()).resolves.toEqual(summary)
  })

  test('lists analyses with encoded status and pagination parameters', async () => {
    let observedUrl: URL | undefined
    server.use(
      http.get('/api/analysis', ({ request }) => {
        observedUrl = new URL(request.url)
        return HttpResponse.json(analysisPageResponse)
      }),
    )

    await expect(
      listAnalyses({ status: 'SUCCESS', page: 2, size: 10 }),
    ).resolves.toEqual(analysisPageResponse)
    expect(observedUrl?.searchParams.get('status')).toBe('SUCCESS')
    expect(observedUrl?.searchParams.get('page')).toBe('2')
    expect(observedUrl?.searchParams.get('size')).toBe('10')
  })

  test('omits an undefined status from analysis-list query parameters', async () => {
    let observedUrl: URL | undefined
    const emptyPage = {
      items: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
    }
    server.use(
      http.get('/api/analysis', ({ request }) => {
        observedUrl = new URL(request.url)
        return HttpResponse.json(emptyPage)
      }),
    )

    await expect(
      listAnalyses({ status: undefined, page: 0, size: 5 }),
    ).resolves.toEqual(emptyPage)
    expect(observedUrl?.searchParams.has('status')).toBe(false)
  })

  test('submits multipart file and text fields without a manual content type', async () => {
    let observedInput: RequestInfo | URL | undefined
    let observedInit: RequestInit | undefined
    const submissionResponse = {
      ...pendingTaskResponse,
      jobTitle: 'Backend Engineer',
      resumeFileName: 'resume.pdf',
    }
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (input: RequestInfo | URL, init?: RequestInit) => {
        observedInput = input
        observedInit = init
        return Response.json(submissionResponse)
      },
    )
    const file = new File(['synthetic resume'], 'resume.pdf', {
      type: 'application/pdf',
    })

    try {
      await expect(
        createAnalysisSubmission({
          file,
          jobTitle: 'Backend Engineer',
          jobContent: 'Java and Redis',
        }),
      ).resolves.toEqual(submissionResponse)
    } finally {
      fetchSpy.mockRestore()
    }

    expect(observedInput).toBe('/api/analysis-submissions')
    expect(observedInit?.method).toBe('POST')
    const submittedHeaders = new Headers(observedInit?.headers)
    expect(submittedHeaders.has('Content-Type')).toBe(false)
    expect(submittedHeaders.get('Accept')).toBe('application/json')
    const submittedBody = observedInit?.body
    expect(submittedBody).toBeInstanceOf(FormData)
    if (submittedBody instanceof FormData) {
      const submittedFile = submittedBody.get('file')
      expect(submittedFile).toBe(file)
      expect(submittedBody.get('jobTitle')).toBe('Backend Engineer')
      expect(submittedBody.get('jobContent')).toBe('Java and Redis')
    }
  })

  test('gets an analysis task with all backend-nullable fields', async () => {
    server.use(
      http.get('/api/analysis/30', () => HttpResponse.json(pendingTaskResponse)),
    )

    await expect(getAnalysisTask(30)).resolves.toEqual(pendingTaskResponse)
  })

  test('retries an analysis task with POST', async () => {
    server.use(
      http.post('/api/analysis/30/retry', () =>
        HttpResponse.json(pendingTaskResponse),
      ),
    )

    await expect(retryAnalysisTask(30)).resolves.toEqual(pendingTaskResponse)
  })

  test('gets the match report using the backend reportContent field', async () => {
    const report = {
      taskId: 30,
      matchScore: 88,
      reportContent: '# 匹配报告',
      createdAt: '2026-07-10T09:05:00',
    }
    server.use(
      http.get('/api/analysis/30/report', () => HttpResponse.json(report)),
    )

    await expect(getMatchReport(30)).resolves.toEqual(report)
  })

  test('gets backend readiness health', async () => {
    server.use(
      http.get('/backend-health', () => HttpResponse.json({ status: 'UP' })),
    )

    await expect(getBackendHealth()).resolves.toEqual({ status: 'UP' })
  })
})

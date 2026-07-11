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
    await apiRequest('/api/header-probe', healthSchema, {
      headers: {
        Accept: 'application/xml',
        'X-Custom-Header': 'object-value',
        'X-Request-Id': 'object-caller-request-id',
      },
    })

    expect(observations.map(({ accept }) => accept)).toEqual([
      'application/json',
      'application/json',
      'application/json',
    ])
    expect(observations.map(({ customHeader }) => customHeader)).toEqual([
      'headers-value',
      'tuple-value',
      'object-value',
    ])
    expect(observations.map(({ token }) => token)).toEqual([null, null, null])
    const requestIds = observations.map(({ requestId }) => requestId)
    expect(requestIds).not.toContain('caller-request-id')
    expect(requestIds).not.toContain('another-caller-request-id')
    expect(requestIds).not.toContain('object-caller-request-id')
    expect(requestIds).toEqual([
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
      expect.stringMatching(/^[0-9a-f-]{36}$/i),
    ])
    expect(new Set(requestIds).size).toBe(3)
  })

  test('uses getRandomValues when randomUUID is unavailable and still sends the request', async () => {
    let observedRequestId: string | null = null
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (_input: RequestInfo | URL, init?: RequestInit) => {
        observedRequestId = new Headers(init?.headers).get('X-Request-Id')
        return Response.json({ status: 'UP' })
      },
    )
    vi.stubGlobal('crypto', {
      getRandomValues: (values: Uint8Array) => {
        values.set(Array.from({ length: 16 }, (_value, index) => index))
        return values
      },
    })

    try {
      await expect(getBackendHealth()).resolves.toEqual({ status: 'UP' })
    } finally {
      fetchSpy.mockRestore()
      vi.unstubAllGlobals()
    }

    expect(observedRequestId).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
  })

  test('omits the request id when crypto capabilities are unavailable and still sends the request', async () => {
    let requestCount = 0
    let observedRequestId: string | null = null
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (_input: RequestInfo | URL, init?: RequestInit) => {
        requestCount += 1
        observedRequestId = new Headers(init?.headers).get('X-Request-Id')
        return Response.json({ status: 'UP' })
      },
    )
    vi.stubGlobal('crypto', {})

    try {
      await expect(
        apiRequest('/backend-health', healthSchema, {
          headers: { 'X-Request-Id': 'caller-supplied-id' },
        }),
      ).resolves.toEqual({ status: 'UP' })
    } finally {
      fetchSpy.mockRestore()
      vi.unstubAllGlobals()
    }

    expect(requestCount).toBe(1)
    expect(observedRequestId).toBeNull()
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
          {
            status: 404,
            headers: { 'X-Request-Id': 'response-404' },
          },
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

  test('uses the response request id when a structured error has a null request id', async () => {
    server.use(
      http.get('/api/analysis/409', () =>
        HttpResponse.json(
          {
            code: 'CONFLICT',
            message: 'Analysis conflict',
            requestId: null,
          },
          {
            status: 409,
            headers: { 'X-Request-Id': 'response-409' },
          },
        ),
      ),
    )

    await expect(getAnalysisTask(409)).rejects.toMatchObject({
      code: 'CONFLICT',
      requestId: 'response-409',
      status: 409,
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

  test('uses the outgoing request id for a non-structured error without a response id', async () => {
    let outgoingRequestId: string | null = null
    server.use(
      http.get('/api/analysis/500', ({ request }) => {
        outgoingRequestId = request.headers.get('X-Request-Id')
        return HttpResponse.text('Internal error', { status: 500 })
      }),
    )

    const error = await getAnalysisTask(500).catch((reason: unknown) => reason)

    expect(outgoingRequestId).toEqual(expect.any(String))
    expect(error).toMatchObject({
      code: 'HTTP_ERROR',
      requestId: outgoingRequestId,
      status: 500,
    })
  })

  test('uses the response request id for a successful response with an invalid schema', async () => {
    server.use(
      http.get('/api/analysis/1', () =>
        HttpResponse.json(
          { taskId: 'not-a-number' },
          { headers: { 'X-Request-Id': 'response-invalid' } },
        ),
      ),
    )

    await expect(getAnalysisTask(1)).rejects.toMatchObject({
      code: 'INVALID_RESPONSE',
      message: '服务返回了无法识别的数据',
      requestId: 'response-invalid',
      status: 200,
    })
  })

  test('uses the outgoing request id when an invalid response has no response id', async () => {
    let outgoingRequestId: string | null = null
    server.use(
      http.get('/api/analysis/2', ({ request }) => {
        outgoingRequestId = request.headers.get('X-Request-Id')
        return HttpResponse.json({ taskId: 'not-a-number' })
      }),
    )

    const error = await getAnalysisTask(2).catch((reason: unknown) => reason)

    expect(outgoingRequestId).toEqual(expect.any(String))
    expect(error).toMatchObject({
      code: 'INVALID_RESPONSE',
      requestId: outgoingRequestId,
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
    let outgoingRequestId: string | null = null
    server.use(
      http.get('/backend-health', ({ request }) => {
        outgoingRequestId = request.headers.get('X-Request-Id')
        return HttpResponse.error()
      }),
    )

    const error = await getBackendHealth().catch((reason: unknown) => reason)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      code: 'NETWORK_ERROR',
      message: '无法连接到服务',
      requestId: outgoingRequestId,
      status: 0,
    })
    if (error instanceof ApiError) {
      expect(error.cause).toBeInstanceOf(TypeError)
    }
  })

  test('maps a successful response body stream failure to a network error with cause', async () => {
    const bodyFailure = new TypeError('Response body stream failed')
    const response = Response.json(
      { status: 'UP' },
      { headers: { 'X-Request-Id': 'response-stream-error' } },
    )
    const jsonSpy = vi.spyOn(response, 'json').mockRejectedValue(bodyFailure)
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

    try {
      const error = await getBackendHealth().catch((reason: unknown) => reason)

      expect(error).toBeInstanceOf(ApiError)
      expect(error).toMatchObject({
        cause: bodyFailure,
        code: 'NETWORK_ERROR',
        name: 'ApiError',
        requestId: 'response-stream-error',
        status: 0,
      })
      expect(Object.getPrototypeOf(error)).toBe(ApiError.prototype)
    } finally {
      jsonSpy.mockRestore()
      fetchSpy.mockRestore()
    }
  })

  test('maps a non-2xx response body stream failure to a network error with the outgoing request id', async () => {
    const bodyFailure = new TypeError('Response decoding failed')
    const response = new Response('Unavailable', { status: 503 })
    const jsonSpy = vi.spyOn(response, 'json').mockRejectedValue(bodyFailure)
    let outgoingRequestId: string | null = null
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (_input: RequestInfo | URL, init?: RequestInit) => {
        outgoingRequestId = new Headers(init?.headers).get('X-Request-Id')
        return response
      },
    )

    try {
      const error = await getBackendHealth().catch((reason: unknown) => reason)

      expect(outgoingRequestId).toEqual(expect.any(String))
      expect(error).toMatchObject({
        cause: bodyFailure,
        code: 'NETWORK_ERROR',
        requestId: outgoingRequestId,
        status: 0,
      })
    } finally {
      jsonSpy.mockRestore()
      fetchSpy.mockRestore()
    }
  })

  test('rethrows an existing ApiError from response processing unchanged', async () => {
    const existingError = new ApiError(
      409,
      'EXISTING_ERROR',
      'Existing error',
      'existing-request-id',
    )
    const response = Response.json({ status: 'UP' })
    const jsonSpy = vi.spyOn(response, 'json').mockRejectedValue(existingError)
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

    try {
      await expect(getBackendHealth()).rejects.toBe(existingError)
    } finally {
      jsonSpy.mockRestore()
      fetchSpy.mockRestore()
    }
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

  test('forwards an explicit signal through the backend health request', async () => {
    const controller = new AbortController()
    let observedSignal: AbortSignal | null | undefined
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async (_input: RequestInfo | URL, init?: RequestInit) => {
        observedSignal = init?.signal
        return new Promise<Response>((_resolve, reject) => {
          observedSignal?.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          )
        })
      },
    )

    try {
      const request = getBackendHealth(controller.signal)
      await Promise.resolve()

      expect(observedSignal).toBe(controller.signal)

      controller.abort()

      await expect(request).rejects.toMatchObject({ name: 'AbortError' })
    } finally {
      fetchSpy.mockRestore()
    }
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

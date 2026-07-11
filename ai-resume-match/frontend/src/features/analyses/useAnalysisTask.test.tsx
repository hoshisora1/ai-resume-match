import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { PropsWithChildren } from 'react'
import { afterEach, describe, expect, test, vi } from 'vitest'

import type { AnalysisStatus } from '../../shared/api/schemas'
import { analysisTaskQueryKey, useAnalysisTask } from './useAnalysisTask'

const TASK_ID = 42
const LOCAL_TIMESTAMP = '2026-07-10T09:00:00'

function taskResponse(status: AnalysisStatus) {
  return {
    taskId: TASK_ID,
    resumeId: 10,
    jobDescriptionId: 20,
    jobTitle: '高级后端工程师',
    resumeFileName: 'synthetic-resume.pdf',
    matchScore: status === 'SUCCESS' ? 88 : null,
    status,
    attemptCount: status === 'PENDING' ? 0 : 1,
    maxAttempts: 3,
    failureCode: status.startsWith('FAILED') ? 'AI_UNAVAILABLE' : null,
    failureMessage: status.startsWith('FAILED') ? 'private backend detail' : null,
    nextRetryAt:
      status === 'FAILED_RETRYABLE' ? '2026-07-10T09:10:00' : null,
    startedAt: status === 'PENDING' ? null : LOCAL_TIMESTAMP,
    completedAt:
      ['PENDING', 'RUNNING'].includes(status) ? null : '2026-07-10T09:05:00',
    createdAt: LOCAL_TIMESTAMP,
    updatedAt: LOCAL_TIMESTAMP,
  }
}

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: Infinity,
        refetchOnWindowFocus: false,
        retry: false,
      },
    },
  })
}

function createWrapper(queryClient: QueryClient) {
  return function QueryWrapper({ children }: PropsWithChildren) {
    return (
      <QueryClientProvider client={queryClient}>
        {children}
      </QueryClientProvider>
    )
  }
}

async function advanceFakeTimersUntil(assertion: () => void) {
  let lastError: unknown

  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      assertion()
      return
    } catch (error) {
      lastError = error
    }

    await act(async () => {
      await Promise.resolve()
      if (vi.getTimerCount() > 0) {
        await vi.advanceTimersToNextTimerAsync()
      }
      await Promise.resolve()
    })
  }

  throw lastError
}

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

test('polls successful active fetches after 2s, 4s, then a capped 8s and stops on success', async () => {
  vi.useFakeTimers()
  const statuses: AnalysisStatus[] = [
    'PENDING',
    'RUNNING',
    'PENDING',
    'RUNNING',
    'SUCCESS',
  ]
  let requestCount = 0
  const requestedAt: number[] = []
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    expect(input).toBe(`/api/analysis/${TASK_ID}`)
    expect(init?.signal).toBeInstanceOf(AbortSignal)
    const status = statuses[Math.min(requestCount, statuses.length - 1)]!
    requestCount += 1
    requestedAt.push(Date.now())
    return Response.json(taskResponse(status))
  })
  const queryClient = createTestQueryClient()

  const { result, unmount } = renderHook(() => useAnalysisTask(TASK_ID), {
    wrapper: createWrapper(queryClient),
  })

  await advanceFakeTimersUntil(() => {
    expect(requestCount).toBe(5)
  })
  await advanceFakeTimersUntil(() => {
    expect(result.current.data?.status).toBe('SUCCESS')
  })
  expect(
    queryClient.getQueryCache().find({
      exact: true,
      queryKey: analysisTaskQueryKey(TASK_ID),
    }),
  ).toBeDefined()
  expect(
    requestedAt.slice(1).map((timestamp, index) =>
      timestamp - requestedAt[index]!,
    ),
  ).toEqual([2_000, 4_000, 8_000, 8_000])

  await act(async () => vi.advanceTimersByTimeAsync(24_000))
  expect(requestCount).toBe(5)

  unmount()
  queryClient.clear()
})

describe('terminal polling states', () => {
  test.each<AnalysisStatus>([
    'SUCCESS',
    'FAILED_RETRYABLE',
    'FAILED_FINAL',
    'CANCELLED',
    'FAILED',
  ])('does not poll again after %s', async (status) => {
    vi.useFakeTimers()
    let requestCount = 0
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      requestCount += 1
      return Response.json(taskResponse(status))
    })
    const queryClient = createTestQueryClient()

    const { result, unmount } = renderHook(() => useAnalysisTask(TASK_ID), {
      wrapper: createWrapper(queryClient),
    })

    await advanceFakeTimersUntil(() => {
      expect(result.current.data?.status).toBe(status)
    })
    await act(async () => vi.advanceTimersByTimeAsync(24_000))
    expect(requestCount).toBe(1)

    unmount()
    queryClient.clear()
  })
})

test('passes the query AbortSignal through and aborts the task request on unmount', async () => {
  let observedSignal: AbortSignal | null | undefined
  vi.spyOn(globalThis, 'fetch').mockImplementation(
    async (_input, init) =>
      new Promise<Response>((_resolve, reject) => {
        observedSignal = init?.signal
        observedSignal?.addEventListener(
          'abort',
          () => reject(new DOMException('Aborted', 'AbortError')),
          { once: true },
        )
      }),
  )
  const queryClient = createTestQueryClient()

  const { unmount } = renderHook(() => useAnalysisTask(TASK_ID), {
    wrapper: createWrapper(queryClient),
  })

  await vi.waitFor(() => {
    expect(observedSignal).toBeInstanceOf(AbortSignal)
  })
  expect(observedSignal?.aborted).toBe(false)

  unmount()

  expect(observedSignal?.aborted).toBe(true)
  queryClient.clear()
})

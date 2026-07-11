import { expect, test, vi } from 'vitest'

import { runCleanupSteps } from './resourceCleanup'

test('attempts every cleanup step when one step fails', async () => {
  const attempted: string[] = []
  const cleanup = runCleanupSteps(
    [
      {
        label: 'vite',
        run: () => {
          attempted.push('vite')
          throw new Error('close failed')
        },
      },
      {
        label: 'upstream',
        run: () => {
          attempted.push('upstream')
        },
      },
      {
        label: 'build directory',
        run: () => {
          attempted.push('build directory')
        },
      },
    ],
    1_000,
  )

  await expect(cleanup).rejects.toThrow('Frontend test cleanup failed')
  expect(attempted).toEqual(['vite', 'upstream', 'build directory'])
})

test('times out a stuck cleanup step without blocking the others', async () => {
  vi.useFakeTimers()
  const attempted: string[] = []

  try {
    const cleanup = runCleanupSteps(
      [
        {
          label: 'stuck',
          run: () => {
            attempted.push('stuck')
            return new Promise<void>(() => undefined)
          },
        },
        {
          label: 'fast',
          run: () => {
            attempted.push('fast')
          },
        },
      ],
      100,
    )
    const settlement = cleanup.then(
      () => null,
      (error: unknown) => error,
    )

    await vi.advanceTimersByTimeAsync(100)
    const cleanupError = await settlement

    expect(cleanupError).toBeInstanceOf(AggregateError)
    if (cleanupError instanceof AggregateError) {
      expect(cleanupError.message).toBe('Frontend test cleanup failed')
    }
    expect(attempted).toEqual(['stuck', 'fast'])
  } finally {
    vi.useRealTimers()
  }
})

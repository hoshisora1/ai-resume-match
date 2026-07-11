import { QueryClient } from '@tanstack/react-query'
import { act, render, screen } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { expect, test } from 'vitest'

import { App } from './App'
import { appRoutes, type AppRouter } from './router'

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: Infinity,
        retry: false,
      },
      mutations: {
        retry: false,
      },
    },
  })
}

function renderTestApp(initialEntries: string[] = ['/']) {
  const queryClient = createTestQueryClient()
  const router: AppRouter = createMemoryRouter(appRoutes, { initialEntries })
  const rendered = render(<App queryClient={queryClient} router={router} />)
  let disposed = false

  return {
    queryClient,
    router,
    dispose: () => {
      if (disposed) {
        return
      }
      disposed = true

      try {
        rendered.unmount()
      } finally {
        try {
          router.dispose()
        } finally {
          queryClient.clear()
        }
      }
    },
  }
}

test('renders the product shell and dashboard route', async () => {
  const app = renderTestApp()

  try {
    expect(await screen.findByRole('link', { name: 'MatchLab' })).toBeVisible()
    expect(screen.getByRole('heading', { name: '分析总览' })).toBeVisible()
  } finally {
    app.dispose()
  }
})

test('isolates router history and query cache between app instances', async () => {
  const cacheKey = ['instance-isolation'] as const
  const first = renderTestApp()

  try {
    first.queryClient.setQueryData(cacheKey, { owner: 'first' })
    expect(first.queryClient.getQueryData(cacheKey)).toEqual({ owner: 'first' })
    await act(async () => {
      await first.router.navigate('/analyses')
    })

    expect(first.router.state.location.pathname).toBe('/analyses')
    expect(screen.getByRole('heading', { name: '分析历史' })).toBeVisible()
  } finally {
    first.dispose()
  }

  const second = renderTestApp()

  try {
    expect(second.router.state.location.pathname).toBe('/')
    expect(second.queryClient.getQueryData(cacheKey)).toBeUndefined()
    expect(screen.getByRole('heading', { name: '分析总览' })).toBeVisible()
  } finally {
    second.dispose()
  }
})

test('dispose cancels pending queries and clears the test cache', async () => {
  const app = renderTestApp()
  const queryKey = ['pending-cleanup'] as const
  let aborted = false
  const queryPromise = app.queryClient.fetchQuery({
    queryKey,
    queryFn: ({ signal }) =>
      new Promise<string>((_resolve, reject) => {
        signal.addEventListener(
          'abort',
          () => {
            aborted = true
            reject(new Error('Query aborted during test cleanup'))
          },
          { once: true },
        )
      }),
  })
  const settlement = queryPromise.then(
    () => 'resolved',
    () => 'rejected',
  )

  try {
    await Promise.resolve()
    expect(app.queryClient.getDefaultOptions()).toMatchObject({
      queries: { gcTime: Infinity, retry: false },
      mutations: { retry: false },
    })
    expect(app.queryClient.getQueryState(queryKey)?.fetchStatus).toBe('fetching')
    expect(app.queryClient.getQueryCache().getAll()).toHaveLength(1)

    app.dispose()

    expect(aborted).toBe(true)
    expect(app.queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(await settlement).toBe('rejected')
  } finally {
    app.dispose()
  }
})

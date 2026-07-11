import { QueryClient } from '@tanstack/react-query'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { expect, test } from 'vitest'

import { server } from '../test/server'
import { App } from './App'
import { backendHealthQueryOptions } from './backendHealthQuery'
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

function renderTestApp(
  initialEntries: string[] = ['/'],
  backendHealth: 'up' | 'error' = 'up',
) {
  server.use(
    http.get('/backend-health', () =>
      backendHealth === 'up'
        ? HttpResponse.json({ status: 'UP' })
        : HttpResponse.error(),
    ),
  )
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

test('renders the responsive product shell navigation and active-page cue', async () => {
  const user = userEvent.setup()
  const app = renderTestApp(['/analyses'])

  try {
    expect(await screen.findByRole('link', { name: 'MatchLab' })).toBeVisible()
    const navigation = screen.getByRole('navigation', { name: '主导航' })
    const dashboardLink = within(navigation).getByRole('link', { name: '总览' })
    const historyLink = within(navigation).getByRole('link', {
      name: '分析记录',
    })
    const createLink = within(navigation).getByRole('link', {
      name: '新建分析',
    })

    expect(navigation).toBeVisible()
    expect(dashboardLink).toHaveAttribute('href', '/')
    expect(historyLink).toHaveAttribute('href', '/analyses')
    expect(createLink).toHaveAttribute('href', '/analyses/new')
    expect(historyLink).toHaveAttribute('aria-current', 'page')
    expect(dashboardLink).not.toHaveAttribute('aria-current')
    expect(screen.getByRole('heading', { name: '分析历史' })).toBeVisible()
    expect(await screen.findByText('API 已连接')).toBeVisible()
    expect(screen.queryByText(/数据库|队列/)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '新建分析' }))

    expect(app.router.state.location.pathname).toBe('/analyses/new')
    expect(screen.getByRole('heading', { name: '新建分析' })).toBeVisible()
  } finally {
    app.dispose()
  }
})

test('shows only the approved unavailable health wording on request failure', async () => {
  const app = renderTestApp(['/'], 'error')

  try {
    expect(await screen.findByText('API 暂不可用')).toBeVisible()
    expect(screen.queryByText(/数据库|队列/)).not.toBeInTheDocument()
  } finally {
    app.dispose()
  }
})

test('uses a stale health query without interval polling', () => {
  expect(backendHealthQueryOptions.staleTime).toBeGreaterThanOrEqual(60_000)
  expect(backendHealthQueryOptions.refetchInterval).toBe(false)
  expect(backendHealthQueryOptions.refetchOnWindowFocus).toBe(true)
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
    expect(
      app.queryClient.getQueryState(backendHealthQueryOptions.queryKey),
    ).toBeDefined()
    expect(app.queryClient.getQueryCache().find({ queryKey })).toBeDefined()

    app.dispose()

    expect(aborted).toBe(true)
    expect(app.queryClient.getQueryCache().getAll()).toHaveLength(0)
    expect(await settlement).toBe('rejected')
  } finally {
    app.dispose()
  }
})

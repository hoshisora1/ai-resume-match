import { QueryClient, focusManager } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { createMemoryRouter, type RouteObject } from 'react-router'
import { expect, test, vi } from 'vitest'

import { server } from '../test/server'
import { App } from './App'
import { AppShell } from './AppShell'
import { backendHealthQueryOptions } from './backendHealthQuery'
import { ErrorBoundary } from './ErrorBoundary'
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
  backendHealth: 'up' | 'error' | 'custom' = 'up',
  routes: RouteObject[] = appRoutes,
) {
  if (backendHealth !== 'custom') {
    server.use(
      http.get('/backend-health', () =>
        backendHealth === 'up'
          ? HttpResponse.json({ status: 'UP' })
          : HttpResponse.error(),
      ),
    )
  }
  const queryClient = createTestQueryClient()
  const router: AppRouter = createMemoryRouter(routes, { initialEntries })
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

function FocusedAnalysisPage() {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  return (
    <div>
      <h1>分析筛选</h1>
      <input aria-label="分析筛选条件" ref={inputRef} />
    </div>
  )
}

function PortalFocusPage({ portalRoot }: { portalRoot: HTMLElement }) {
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    inputRef.current?.focus()
  }, [])

  return createPortal(
    <div aria-label="分析快捷操作" role="dialog">
      <input aria-label="快捷操作名称" ref={inputRef} />
    </div>,
    portalRoot,
  )
}

function focusTestRoutes(analysisElement: ReactNode) {
  return [
    {
      path: '/',
      element: (
        <ErrorBoundary>
          <AppShell />
        </ErrorBoundary>
      ),
      children: [
        { index: true, element: <h1>分析总览</h1> },
        { path: 'analyses', element: analysisElement },
      ],
    },
  ] satisfies RouteObject[]
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

    const topbar = screen
      .getAllByRole('banner')
      .find((banner) => banner.classList.contains('app-topbar'))
    expect(topbar).toBeDefined()
    const topbarCreateLink = within(topbar!).getByRole(
      'link',
      { name: '新建分析' },
    )
    expect(topbarCreateLink).toHaveAttribute('href', '/analyses/new')
    await user.click(topbarCreateLink)

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
    expect(screen.getByRole('button', { name: '重新检查 API' })).toHaveAttribute(
      'title',
      '重新检查 API',
    )
    expect(screen.getByText('API 暂不可用').closest('.backend-health')).toHaveAttribute(
      'title',
      expect.stringMatching(/^最后检查：/),
    )
  } finally {
    app.dispose()
  }
})

test('shows a neutral health label while the first request is pending', async () => {
  let releaseResponse: () => void = () => undefined
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve
  })
  server.use(
    http.get('/backend-health', async () => {
      await responseGate
      return HttpResponse.json({ status: 'UP' })
    }),
  )
  const app = renderTestApp(['/'], 'custom')

  try {
    expect(screen.getByText('正在检查 API')).toBeVisible()
    expect(screen.queryByText('API 暂不可用')).not.toBeInTheDocument()

    releaseResponse()

    expect(await screen.findByText('API 已连接')).toBeVisible()
  } finally {
    releaseResponse()
    app.dispose()
  }
})

test('retries an unavailable health request and recovers to connected', async () => {
  let requestCount = 0
  server.use(
    http.get('/backend-health', () => {
      requestCount += 1
      return requestCount === 1
        ? HttpResponse.error()
        : HttpResponse.json({ status: 'UP' })
    }),
  )
  const user = userEvent.setup()
  const app = renderTestApp(['/'], 'custom')

  try {
    expect(await screen.findByText('API 暂不可用')).toBeVisible()

    await user.click(screen.getByRole('button', { name: '重新检查 API' }))

    expect(await screen.findByText('API 已连接')).toBeVisible()
    expect(screen.queryByRole('button', { name: '重新检查 API' })).not.toBeInTheDocument()
    expect(requestCount).toBe(2)
  } finally {
    app.dispose()
  }
})

test('uses low-frequency foreground-only health refresh settings', () => {
  expect(backendHealthQueryOptions.staleTime).toBeGreaterThanOrEqual(60_000)
  expect(backendHealthQueryOptions.refetchInterval).toBe(60_000)
  expect(backendHealthQueryOptions.refetchIntervalInBackground).toBe(false)
  expect(backendHealthQueryOptions.refetchOnWindowFocus).toBe(true)
  expect(backendHealthQueryOptions.refetchOnReconnect).toBe(true)
})

test('refetches a stale successful health query when focus returns', async () => {
  let requestCount = 0
  let now = Date.now()
  const dateNow = vi.spyOn(Date, 'now').mockImplementation(() => now)
  server.use(
    http.get('/backend-health', () => {
      requestCount += 1
      return HttpResponse.json({ status: 'UP' })
    }),
  )
  const app = renderTestApp(['/'], 'custom')

  try {
    expect(await screen.findByText('API 已连接')).toBeVisible()
    expect(requestCount).toBe(1)

    now += 60_001
    await act(async () => {
      focusManager.setFocused(false)
      focusManager.setFocused(true)
    })

    await waitFor(() => {
      expect(requestCount).toBe(2)
    })
  } finally {
    focusManager.setFocused(undefined)
    dateNow.mockRestore()
    app.dispose()
  }
})

test('keeps analysis history active for detail routes but not the new route', async () => {
  const detailApp = renderTestApp(['/analyses/42'])

  try {
    expect(await screen.findByRole('heading', { name: '分析详情' })).toBeVisible()
    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(
      within(navigation).getByRole('link', { name: '分析记录' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(
      within(navigation).getByRole('link', { name: '新建分析' }),
    ).not.toHaveAttribute('aria-current')
  } finally {
    detailApp.dispose()
  }

  const newApp = renderTestApp(['/analyses/new'])

  try {
    expect(await screen.findByRole('heading', { name: '新建分析' })).toBeVisible()
    const navigation = screen.getByRole('navigation', { name: '主导航' })
    expect(
      within(navigation).getByRole('link', { name: '新建分析' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(
      within(navigation).getByRole('link', { name: '分析记录' }),
    ).not.toHaveAttribute('aria-current')
  } finally {
    newApp.dispose()
  }
})

test('offers skip navigation and focuses main only after explicit navigation', async () => {
  const user = userEvent.setup()
  const app = renderTestApp()

  try {
    expect(await screen.findByText('API 已连接')).toBeVisible()
    const main = screen.getByRole('main')
    expect(main).toHaveAttribute('id', 'main-content')
    expect(main).toHaveAttribute('tabindex', '-1')
    expect(main).not.toHaveFocus()

    await user.tab()

    const skipLink = screen.getByRole('link', { name: '跳到主要内容' })
    expect(skipLink).toHaveFocus()

    await user.keyboard('{Enter}')

    expect(main).toHaveFocus()

    const historyLink = screen.getByRole('link', { name: '分析记录' })
    historyLink.focus()
    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(app.router.state.location.pathname).toBe('/analyses')
      expect(main).toHaveFocus()
    })
  } finally {
    app.dispose()
  }
})

test('preserves focus established by the destination route inside main', async () => {
  const user = userEvent.setup()
  const app = renderTestApp(
    ['/'],
    'up',
    focusTestRoutes(<FocusedAnalysisPage />),
  )

  try {
    expect(await screen.findByText('API 已连接')).toBeVisible()

    await user.click(screen.getByRole('link', { name: '分析记录' }))

    const destinationInput = await screen.findByRole('textbox', {
      name: '分析筛选条件',
    })
    expect(destinationInput).toHaveFocus()
    expect(screen.getByRole('main')).not.toHaveFocus()
  } finally {
    app.dispose()
  }
})

test('preserves focus established by a destination dialog portal', async () => {
  const portalRoot = document.createElement('div')
  document.body.append(portalRoot)
  const user = userEvent.setup()
  const app = renderTestApp(
    ['/'],
    'up',
    focusTestRoutes(<PortalFocusPage portalRoot={portalRoot} />),
  )

  try {
    expect(await screen.findByText('API 已连接')).toBeVisible()

    await user.click(screen.getByRole('link', { name: '分析记录' }))

    const portalInput = await screen.findByRole('textbox', {
      name: '快捷操作名称',
    })
    expect(portalInput).toHaveFocus()
    expect(screen.getByRole('main')).not.toHaveFocus()
  } finally {
    app.dispose()
    portalRoot.remove()
  }
})

test('dispose aborts the actual in-flight backend health request', async () => {
  let observedSignal: AbortSignal | null | undefined
  let markStarted: () => void = () => undefined
  let aborted = false
  const started = new Promise<void>((resolve) => {
    markStarted = resolve
  })
  const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
    async (_input: RequestInfo | URL, init?: RequestInit) => {
      observedSignal = init?.signal
      markStarted()

      return new Promise<Response>((_resolve, reject) => {
        observedSignal?.addEventListener(
          'abort',
          () => {
            aborted = true
            reject(new DOMException('Aborted', 'AbortError'))
          },
          { once: true },
        )
      })
    },
  )
  const app = renderTestApp(['/'], 'custom')

  try {
    await started
    expect(observedSignal).toBeInstanceOf(AbortSignal)
    expect(observedSignal?.aborted).toBe(false)

    app.dispose()

    expect(observedSignal?.aborted).toBe(true)
    expect(aborted).toBe(true)
  } finally {
    app.dispose()
    fetchSpy.mockRestore()
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

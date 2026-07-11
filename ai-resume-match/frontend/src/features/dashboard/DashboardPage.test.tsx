import type { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterEach, expect, test } from 'vitest'
import { createMemoryRouter } from 'react-router'

import { App } from '../../app/App'
import { createQueryClient } from '../../app/queryClient'
import { appRoutes, type AppRouter } from '../../app/router'
import { server } from '../../test/server'

const summaryResponse = {
  totalCount: 12,
  successCount: 10,
  inProgressCount: 1,
  retryableFailureCount: 1,
  averageMatchScore: 82.4,
}

const recentResponse = {
  items: [
    {
      taskId: 1,
      jobTitle: '高级后端工程师',
      resumeFileName: 'resume.pdf',
      status: 'SUCCESS',
      matchScore: 88,
      attemptCount: 1,
      maxAttempts: 3,
      failureCode: null,
      createdAt: '2026-07-10T09:00:00',
      updatedAt: '2026-07-10T09:05:00',
      completedAt: '2026-07-10T09:05:00',
    },
    {
      taskId: 2,
      jobTitle: '数据平台工程师',
      resumeFileName: null,
      status: 'PENDING',
      matchScore: null,
      attemptCount: 0,
      maxAttempts: 3,
      failureCode: null,
      createdAt: '2026-07-10T10:00:00',
      updatedAt: '2026-07-10T10:00:00',
      completedAt: null,
    },
  ],
  page: 0,
  size: 5,
  totalElements: 2,
  totalPages: 1,
}

interface RenderedApp {
  queryClient: QueryClient
  router: AppRouter
  unmount: () => void
}

const renderedApps: RenderedApp[] = []

function createProductionTestQueryClient() {
  const queryClient = createQueryClient()
  const defaultOptions = queryClient.getDefaultOptions()
  queryClient.setDefaultOptions({
    ...defaultOptions,
    queries: {
      ...defaultOptions.queries,
      gcTime: Infinity,
      retryDelay: 0,
    },
  })
  return queryClient
}

function renderDashboard() {
  server.use(
    http.get('/backend-health', () => HttpResponse.json({ status: 'UP' })),
  )
  const queryClient = createProductionTestQueryClient()
  const router: AppRouter = createMemoryRouter(appRoutes, {
    initialEntries: ['/'],
  })
  const rendered = render(<App queryClient={queryClient} router={router} />)
  let isUnmounted = false
  const unmount = () => {
    if (!isUnmounted) {
      isUnmounted = true
      rendered.unmount()
    }
  }
  renderedApps.push({ queryClient, router, unmount })

  return { queryClient, router, unmount }
}

afterEach(() => {
  for (const app of renderedApps.splice(0)) {
    app.unmount()
    app.router.dispose()
    app.queryClient.clear()
  }
})

test('loads summary and recent analyses independently in parallel with stable query keys', async () => {
  let releaseRequests: () => void = () => undefined
  const requestGate = new Promise<void>((resolve) => {
    releaseRequests = resolve
  })
  let summaryRequests = 0
  let recentRequests = 0

  server.use(
    http.get('/api/analysis/summary', async () => {
      summaryRequests += 1
      await requestGate
      return HttpResponse.json(summaryResponse)
    }),
    http.get('/api/analysis', async ({ request }) => {
      const url = new URL(request.url)
      recentRequests += 1
      expect(url.searchParams.has('status')).toBe(false)
      expect(url.searchParams.get('page')).toBe('0')
      expect(url.searchParams.get('size')).toBe('5')
      await requestGate
      return HttpResponse.json(recentResponse)
    }),
  )

  try {
    const { queryClient } = renderDashboard()

    expect(screen.getByTestId('dashboard-metrics-skeleton')).toBeVisible()
    expect(screen.getAllByTestId('analysis-row-skeleton')).toHaveLength(5)

    await waitFor(() => {
      expect(summaryRequests).toBe(1)
      expect(recentRequests).toBe(1)
    })

    expect(
      queryClient
        .getQueryCache()
        .getAll()
        .map((query) => query.queryKey),
    ).toEqual(
      expect.arrayContaining([
        ['analysis-summary'],
        ['analyses', { status: undefined, page: 0, size: 5 }],
      ]),
    )
  } finally {
    releaseRequests()
  }
})

test('renders summary metrics and a semantic recent-analysis table', async () => {
  server.use(
    http.get('/api/analysis/summary', () => HttpResponse.json(summaryResponse)),
    http.get('/api/analysis', () => HttpResponse.json(recentResponse)),
  )

  renderDashboard()

  const summary = await screen.findByRole('region', { name: '分析概况' })
  expect(await within(summary).findByText('12')).toBeVisible()
  expect(within(summary).getByText('10')).toBeVisible()
  expect(within(summary).getByText('82.4')).toBeVisible()

  const table = await screen.findByRole('table', { name: '最近五条分析' })
  expect(within(table).getAllByRole('columnheader')).toHaveLength(5)
  expect(
    within(table).getByRole('link', { name: '高级后端工程师' }),
  ).toHaveAttribute('href', '/analyses/1')
  expect(within(table).getByText('88.0')).toBeVisible()
  expect(within(table).getByText('已完成')).toBeVisible()
  expect(within(table).getByText('2026-07-10 09:05')).toBeVisible()
  expect(screen.getByRole('link', { name: '查看全部' })).toHaveAttribute(
    'href',
    '/analyses',
  )
  expect(within(table).getByText('resume.pdf')).toHaveAttribute(
    'title',
    'resume.pdf',
  )

  const pendingRow = within(table)
    .getByRole('link', { name: '数据平台工程师' })
    .closest('tr')
  expect(pendingRow).not.toBeNull()
  expect(within(pendingRow!).getAllByText('—')).toHaveLength(3)
})

test('exhausts the production summary retry once before a manual retry succeeds', async () => {
  let summaryRequests = 0
  server.use(
    http.get('/api/analysis/summary', () => {
      summaryRequests += 1
      return summaryRequests <= 2
        ? HttpResponse.json(
            { code: 'SUMMARY_UNAVAILABLE', message: 'Unavailable', requestId: null },
            { status: 503 },
          )
        : HttpResponse.json(summaryResponse)
    }),
    http.get('/api/analysis', () => HttpResponse.json(recentResponse)),
  )
  const user = userEvent.setup()

  renderDashboard()

  const summary = await screen.findByRole('region', { name: '分析概况' })
  expect(await within(summary).findByRole('alert')).toHaveTextContent(
    '概况加载失败',
  )
  expect(
    await screen.findByRole('link', { name: '高级后端工程师' }),
  ).toBeVisible()
  expect(summaryRequests).toBe(2)

  await user.click(within(summary).getByRole('button', { name: '重试' }))

  expect(await within(summary).findByText('12')).toBeVisible()
  expect(summaryRequests).toBe(3)
})

test('keeps loaded metrics available when recent analyses fail', async () => {
  server.use(
    http.get('/api/analysis/summary', () => HttpResponse.json(summaryResponse)),
    http.get('/api/analysis', () =>
      HttpResponse.json(
        { code: 'LIST_UNAVAILABLE', message: 'Unavailable', requestId: null },
        { status: 503 },
      ),
    ),
  )

  renderDashboard()

  const summary = await screen.findByRole('region', { name: '分析概况' })
  expect(await within(summary).findByText('12')).toBeVisible()
  const recent = screen.getByRole('region', { name: '最近分析' })
  expect(await within(recent).findByRole('alert')).toHaveTextContent(
    '近期记录加载失败',
  )
})

test('shows a create-analysis action when there are no recent records', async () => {
  server.use(
    http.get('/api/analysis/summary', () => HttpResponse.json(summaryResponse)),
    http.get('/api/analysis', () =>
      HttpResponse.json({
        items: [],
        page: 0,
        size: 5,
        totalElements: 0,
        totalPages: 0,
      }),
    ),
  )
  const user = userEvent.setup()
  const { router } = renderDashboard()

  const recent = await screen.findByRole('region', { name: '最近分析' })
  expect(await within(recent).findByText('还没有近期分析')).toBeVisible()

  const createLink = within(recent).getByRole('link', { name: '新建分析' })
  expect(createLink).toHaveAttribute('href', '/analyses/new')
  await user.click(createLink)

  expect(router.state.location.pathname).toBe('/analyses/new')
})

test('aborts a slow summary request when the dashboard unmounts', async () => {
  let markSummaryStarted: () => void = () => undefined
  const summaryStarted = new Promise<void>((resolve) => {
    markSummaryStarted = resolve
  })
  let releaseSummary: () => void = () => undefined
  const summaryRelease = new Promise<void>((resolve) => {
    releaseSummary = resolve
  })
  let markSummaryFinished: () => void = () => undefined
  const summaryFinished = new Promise<void>((resolve) => {
    markSummaryFinished = resolve
  })
  let summaryAborted = false

  server.use(
    http.get('/api/analysis/summary', async ({ request }) => {
      markSummaryStarted()
      try {
        await Promise.race([
          summaryRelease,
          new Promise<void>((resolve) => {
            request.signal.addEventListener(
              'abort',
              () => {
                summaryAborted = true
                resolve()
              },
              { once: true },
            )
          }),
        ])
        return HttpResponse.json(summaryResponse)
      } finally {
        markSummaryFinished()
      }
    }),
    http.get('/api/analysis', () => HttpResponse.json(recentResponse)),
  )

  try {
    const { unmount } = renderDashboard()
    await summaryStarted

    unmount()

    await waitFor(() => {
      expect(summaryAborted).toBe(true)
    })
  } finally {
    releaseSummary()
    await summaryFinished
  }
})

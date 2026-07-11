import { QueryClient } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'
import { HttpResponse, http } from 'msw'
import { afterEach, expect, test } from 'vitest'
import { createMemoryRouter } from 'react-router'

import { App } from '../../app/App'
import { appRoutes, type AppRouter } from '../../app/router'
import { server } from '../../test/server'

const longJobTitle =
  '负责跨区域数据平台与实时流处理基础设施建设的高级后端工程师（核心交易与稳定性方向）'

const baseItem = {
  taskId: 41,
  jobTitle: longJobTitle,
  resumeFileName: 'candidate-resume.pdf',
  status: 'SUCCESS',
  matchScore: 88,
  attemptCount: 1,
  maxAttempts: 3,
  failureCode: null,
  createdAt: '2026-07-10T09:00:00',
  updatedAt: '2026-07-10T09:05:00',
  completedAt: '2026-07-10T09:05:00',
} as const

interface RenderedApp {
  queryClient: QueryClient
  router: AppRouter
  unmount: () => void
}

const renderedApps: RenderedApp[] = []

function renderHistory(initialEntry = '/analyses') {
  server.use(
    http.get('/backend-health', () => HttpResponse.json({ status: 'UP' })),
  )
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { gcTime: Infinity, retry: false },
      mutations: { retry: false },
    },
  })
  const router: AppRouter = createMemoryRouter(appRoutes, {
    initialEntries: [initialEntry],
  })
  const rendered = render(<App queryClient={queryClient} router={router} />)
  renderedApps.push({ queryClient, router, unmount: rendered.unmount })

  return { queryClient, router }
}

function pageResponse({
  items = [baseItem],
  page = 0,
  size = 20,
  totalElements = items.length,
}: {
  items?: Array<Record<string, unknown>>
  page?: number
  size?: number
  totalElements?: number
} = {}) {
  return {
    items,
    page,
    size,
    totalElements,
    totalPages: Math.ceil(totalElements / size),
  }
}

afterEach(() => {
  for (const app of renderedApps.splice(0)) {
    app.unmount()
    app.router.dispose()
    app.queryClient.clear()
  }
})

test('keeps a six-row history table footprint while data is loading', async () => {
  let releaseRequest: () => void = () => undefined
  const requestGate = new Promise<void>((resolve) => {
    releaseRequest = resolve
  })
  server.use(
    http.get('/api/analysis', async () => {
      await requestGate
      return HttpResponse.json(pageResponse())
    }),
  )

  try {
    renderHistory()

    expect(
      await screen.findByRole('status', { name: '正在加载分析历史' }),
    ).toBeVisible()
    expect(screen.getAllByTestId('analysis-row-skeleton')).toHaveLength(6)
  } finally {
    releaseRequest()
  }
})

test('normalizes invalid URL filters with replace and requests the canonical query', async () => {
  let observedUrl: URL | undefined
  server.use(
    http.get('/api/analysis', ({ request }) => {
      observedUrl = new URL(request.url)
      return HttpResponse.json(pageResponse({ items: [], totalElements: 0 }))
    }),
  )

  const { router } = renderHistory(
    '/analyses?status=UNKNOWN&page=-2.5&size=19&ignored=value',
  )

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=0&size=20')
    expect(router.state.historyAction).toBe('REPLACE')
  })
  expect(observedUrl?.searchParams.has('status')).toBe(false)
  expect(observedUrl?.searchParams.get('page')).toBe('0')
  expect(observedUrl?.searchParams.get('size')).toBe('20')
})

test('uses the URL as the filter source and exposes every backend status label', async () => {
  server.use(
    http.get('/api/analysis', ({ request }) => {
      const url = new URL(request.url)
      return HttpResponse.json(
        pageResponse({
          page: Number(url.searchParams.get('page')),
          size: Number(url.searchParams.get('size')),
        }),
      )
    }),
  )
  const { router } = renderHistory()

  const statusSelect = await screen.findByRole('combobox', { name: '状态' })
  expect(
    within(statusSelect).getAllByRole('option').map((option) => option.textContent),
  ).toEqual([
    '全部状态',
    '待处理',
    '分析中',
    '已完成',
    '可重试失败',
    '最终失败',
    '已取消',
    '分析失败',
  ])

  await router.navigate('/analyses?status=RUNNING&page=0&size=50')

  await waitFor(() => {
    expect(statusSelect).toHaveValue('RUNNING')
    expect(screen.getByRole('combobox', { name: '每页数量' })).toHaveValue('50')
  })
})

test('updates and clears status in the URL without sending an empty status', async () => {
  const observedUrls: URL[] = []
  server.use(
    http.get('/api/analysis', ({ request }) => {
      const url = new URL(request.url)
      observedUrls.push(url)
      return HttpResponse.json(
        pageResponse({
          items: [],
          page: Number(url.searchParams.get('page')),
          size: Number(url.searchParams.get('size')),
          totalElements: 0,
        }),
      )
    }),
  )
  const user = userEvent.setup()
  const { queryClient, router } = renderHistory()

  const statusSelect = await screen.findByRole('combobox', { name: '状态' })
  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=0&size=20')
  })

  await user.selectOptions(statusSelect, 'SUCCESS')

  await waitFor(() => {
    expect(router.state.location.search).toBe('?status=SUCCESS&page=0&size=20')
  })
  expect(observedUrls.at(-1)?.searchParams.get('status')).toBe('SUCCESS')
  expect(
    queryClient
      .getQueryCache()
      .getAll()
      .map((query) => query.queryKey),
  ).toContainEqual([
    'analyses',
    { status: 'SUCCESS', page: 0, size: 20 },
  ])

  await user.selectOptions(statusSelect, '')

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=0&size=20')
  })
  expect(observedUrls.at(-1)?.searchParams.has('status')).toBe(false)
})

test('updates page and page size in the URL', async () => {
  server.use(
    http.get('/api/analysis', ({ request }) => {
      const url = new URL(request.url)
      const page = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      return HttpResponse.json(
        pageResponse({ page, size, totalElements: 45 }),
      )
    }),
  )
  const user = userEvent.setup()
  const { router } = renderHistory()

  expect(await screen.findByText('第 1 / 3 页')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '下一页' }))

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=1&size=20')
  })

  await user.selectOptions(
    screen.getByRole('combobox', { name: '每页数量' }),
    '50',
  )

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=0&size=50')
  })
})

test('accepts the Pagination owner correction for an out-of-range page', async () => {
  server.use(
    http.get('/api/analysis', ({ request }) => {
      const url = new URL(request.url)
      const page = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      return HttpResponse.json(
        pageResponse({
          items: page === 8 ? [] : [baseItem],
          page,
          size,
          totalElements: 45,
        }),
      )
    }),
  )
  const { router } = renderHistory('/analyses?page=8&size=20')

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=2&size=20')
    expect(router.state.historyAction).toBe('REPLACE')
  })
  await waitFor(() => {
    expect(screen.getByText('第 3 / 3 页')).toBeVisible()
  })
})

test('distinguishes an empty history from an empty filtered result', async () => {
  server.use(
    http.get('/api/analysis', ({ request }) => {
      const url = new URL(request.url)
      return HttpResponse.json(
        pageResponse({
          items: [],
          page: Number(url.searchParams.get('page')),
          size: Number(url.searchParams.get('size')),
          totalElements: 0,
        }),
      )
    }),
  )
  const user = userEvent.setup()
  const { router } = renderHistory()

  const history = await screen.findByRole('region', { name: '分析历史' })
  expect(await within(history).findByText('还没有分析记录')).toBeVisible()
  expect(
    within(history).getByRole('button', { name: '新建分析' }),
  ).toBeVisible()

  await user.selectOptions(screen.getByRole('combobox', { name: '状态' }), 'FAILED')

  expect(await screen.findByText('当前条件无结果')).toBeVisible()
  expect(screen.getByText('分析失败')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '清除筛选' }))

  await waitFor(() => {
    expect(router.state.location.search).toBe('?page=0&size=20')
  })
})

test('retries an API error without changing the current URL', async () => {
  let requestCount = 0
  server.use(
    http.get('/api/analysis', () => {
      requestCount += 1
      return requestCount === 1
        ? HttpResponse.json(
            { code: 'LIST_UNAVAILABLE', message: 'Unavailable', requestId: null },
            { status: 503 },
          )
        : HttpResponse.json(
            pageResponse({ page: 1, totalElements: 21 }),
          )
    }),
  )
  const user = userEvent.setup()
  const { router } = renderHistory(
    '/analyses?status=SUCCESS&page=1&size=20',
  )

  expect(await screen.findByRole('alert')).toHaveTextContent('分析历史加载失败')
  const searchBeforeRetry = router.state.location.search

  await user.click(screen.getByRole('button', { name: '重试' }))

  expect(await screen.findByRole('link', { name: longJobTitle })).toBeVisible()
  expect(router.state.location.search).toBe(searchBeforeRetry)
  expect(requestCount).toBe(2)
})

test('keeps the full long title accessible and formats nullable row data stably', async () => {
  server.use(
    http.get('/api/analysis', () =>
      HttpResponse.json(
        pageResponse({
          items: [
            {
              ...baseItem,
              resumeFileName: null,
              matchScore: null,
              completedAt: null,
            },
          ],
        }),
      ),
    ),
  )

  renderHistory()

  const table = await screen.findByRole('table', { name: '分析历史' })
  const titleLink = within(table).getByRole('link', { name: longJobTitle })
  expect(titleLink).toHaveAttribute('href', '/analyses/41')
  expect(titleLink).toHaveTextContent(longJobTitle)
  expect(titleLink).toHaveAttribute('title', longJobTitle)

  const row = titleLink.closest('tr')
  expect(row).not.toBeNull()
  expect(within(row!).getAllByText('—')).toHaveLength(3)
  expect(within(row!).getByText('已完成')).toBeVisible()
})

test('mobile table CSS hides only resume and completion columns without replacing the table', () => {
  const cssPath = resolve(
    cwd(),
    'src/features/analyses/analysis-table.css',
  )
  expect(existsSync(cssPath)).toBe(true)
  if (!existsSync(cssPath)) {
    return
  }

  const css = readFileSync(cssPath, 'utf8')
  const mobileStyles = css.slice(css.indexOf('@media (max-width: 48rem)'))

  expect(css).toMatch(/\.analysis-table\s*\{[^}]*table-layout:\s*fixed;/s)
  expect(css).toMatch(
    /\.analysis-table-wrap\s*\{[^}]*max-width:\s*100%;[^}]*min-height:\s*26rem;/s,
  )
  expect(mobileStyles).toMatch(
    /\.analysis-table__(?:col|cell)--resume[\s\S]*display:\s*none;/,
  )
  expect(mobileStyles).toMatch(
    /\.analysis-table__(?:col|cell)--completed[\s\S]*display:\s*none;/,
  )
  expect(mobileStyles).not.toMatch(
    /\.analysis-table__(?:col|cell)--(?:title|score|status)[^{]*\{[^}]*display:\s*none;/s,
  )
  expect(mobileStyles).not.toMatch(/\.analysis-table\s*\{[^}]*display:\s*none;/s)
})

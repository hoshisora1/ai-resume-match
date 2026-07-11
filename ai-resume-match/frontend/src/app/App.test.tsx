import { act, render, screen } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { expect, test } from 'vitest'

import { App } from './App'
import { createQueryClient } from './queryClient'
import { appRoutes, type AppRouter } from './router'

function renderTestApp(initialEntries: string[] = ['/']) {
  const queryClient = createQueryClient()
  const router: AppRouter = createMemoryRouter(appRoutes, { initialEntries })
  const rendered = render(<App queryClient={queryClient} router={router} />)

  return {
    queryClient,
    router,
    dispose: () => {
      rendered.unmount()
      router.dispose()
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
    expect(first.queryClient.getDefaultOptions()).toMatchObject({
      queries: { retry: 1 },
      mutations: { retry: 0 },
    })
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

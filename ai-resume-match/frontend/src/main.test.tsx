import { act, screen } from '@testing-library/react'
import type { Root, RootOptions } from 'react-dom/client'
import { expect, test, vi } from 'vitest'

const rootCapture = vi.hoisted(() => ({
  options: undefined as RootOptions | undefined,
  root: undefined as Root | undefined,
}))

vi.mock('react-dom/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-dom/client')>()

  return {
    ...actual,
    createRoot(
      container: Parameters<typeof actual.createRoot>[0],
      options?: RootOptions,
    ) {
      rootCapture.options = options
      rootCapture.root = actual.createRoot(container, options)
      return rootCapture.root
    },
  }
})

vi.mock('./app/App', async () => {
  const { ErrorBoundary } = await import('./app/ErrorBoundary')

  function BrokenProductionView(): never {
    throw new Error('synthetic-private-render-error')
  }

  return {
    App() {
      return (
        <ErrorBoundary onReload={() => undefined}>
          <BrokenProductionView />
        </ErrorBoundary>
      )
    },
  }
})

vi.mock('./app/queryClient', () => ({
  createQueryClient: () => ({}),
}))

vi.mock('./app/router', () => ({
  createAppBrowserRouter: () => ({}),
}))

test('production root suppresses boundary-caught details and still renders the fallback', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  const consoleLog = vi.spyOn(console, 'log').mockImplementation(() => undefined)
  const rootElement = document.createElement('div')
  rootElement.id = 'root'
  document.body.append(rootElement)

  try {
    await act(async () => {
      await import('./main')
    })

    expect(screen.getByRole('alert')).toHaveTextContent('页面暂时无法显示')
    expect(screen.queryByText('synthetic-private-render-error')).not.toBeInTheDocument()
    expect(consoleError.mock.calls).toHaveLength(0)
    expect(consoleLog.mock.calls).toHaveLength(0)
    expect(rootCapture.options?.onCaughtError).toEqual(expect.any(Function))
    expect(rootCapture.options).not.toHaveProperty('onUncaughtError')
  } finally {
    await act(async () => {
      rootCapture.root?.unmount()
    })
    rootElement.remove()
    consoleError.mockRestore()
    consoleLog.mockRestore()
  }
})

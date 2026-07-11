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

test('production root safely handles every React error category and still renders the fallback', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  const consoleLog = vi.spyOn(console, 'log').mockImplementation(() => undefined)
  const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
  const reportError = vi.fn()
  vi.stubGlobal('reportError', reportError)
  const rootElement = document.createElement('div')
  rootElement.id = 'root'
  document.body.append(rootElement)

  try {
    await act(async () => {
      await import('./main')
    })

    expect(screen.getByRole('alert')).toHaveTextContent('页面暂时无法显示')
    expect(screen.queryByText('synthetic-private-render-error')).not.toBeInTheDocument()

    const options = rootCapture.options
    expect(options?.onCaughtError).toEqual(expect.any(Function))
    expect(options?.onUncaughtError).toEqual(expect.any(Function))
    expect(options?.onRecoverableError).toEqual(expect.any(Function))
    expect(options?.onCaughtError).toBe(options?.onUncaughtError)
    expect(options?.onCaughtError).toBe(options?.onRecoverableError)

    const privateError = Object.assign(new Error('private-error-message'), {
      props: { resume: 'private-resume-content' },
    })
    options?.onCaughtError?.(privateError, {
      componentStack: 'private-component-stack',
    })
    options?.onUncaughtError?.(privateError, {
      componentStack: 'private-component-stack',
    })
    options?.onRecoverableError?.(privateError, {
      componentStack: 'private-component-stack',
    })

    expect(consoleError.mock.calls).toHaveLength(0)
    expect(consoleLog.mock.calls).toHaveLength(0)
    expect(consoleWarn.mock.calls).toHaveLength(0)
    expect(reportError).not.toHaveBeenCalled()
    expect(document.body).not.toHaveTextContent('private-error-message')
    expect(document.body).not.toHaveTextContent('private-component-stack')
    expect(document.body).not.toHaveTextContent('private-resume-content')
  } finally {
    await act(async () => {
      rootCapture.root?.unmount()
    })
    rootElement.remove()
    consoleError.mockRestore()
    consoleLog.mockRestore()
    consoleWarn.mockRestore()
    vi.unstubAllGlobals()
  }
})

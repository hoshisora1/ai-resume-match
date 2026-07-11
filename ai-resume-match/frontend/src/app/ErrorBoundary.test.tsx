import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'

import { ErrorBoundary } from './ErrorBoundary'
import { reactRootOptions } from './reactRootOptions'

const sensitiveErrorMessage = 'resume text must stay private'

function BrokenView(): never {
  throw new Error(sensitiveErrorMessage)
}

function replaceClipboard(clipboard: Pick<Clipboard, 'writeText'> | undefined) {
  const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: clipboard,
  })

  return () => {
    if (descriptor === undefined) {
      Reflect.deleteProperty(navigator, 'clipboard')
    } else {
      Object.defineProperty(navigator, 'clipboard', descriptor)
    }
  }
}

test('renders a restrained fallback without exposing the render error', async () => {
  const onReload = vi.fn()
  const user = userEvent.setup()

  render(
    <ErrorBoundary onReload={onReload}>
      <BrokenView />
    </ErrorBoundary>,
    { onCaughtError: reactRootOptions.onCaughtError },
  )

  const main = screen.getByRole('main')
  expect(main).toHaveAttribute('id', 'main-content')
  expect(within(main).getByRole('alert')).toHaveTextContent('页面暂时无法显示')
  expect(screen.queryByText(sensitiveErrorMessage)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '刷新页面' })).toBeVisible()
  expect(screen.getByRole('button', { name: '复制页面地址' })).toHaveAttribute(
    'title',
    '复制页面地址',
  )

  await user.click(screen.getByRole('button', { name: '刷新页面' }))

  expect(onReload).toHaveBeenCalledTimes(1)
})

test('copies only the current page address from the fallback action', async () => {
  const user = userEvent.setup()

  render(
    <ErrorBoundary onReload={vi.fn()}>
      <BrokenView />
    </ErrorBoundary>,
    { onCaughtError: reactRootOptions.onCaughtError },
  )

  await user.click(screen.getByRole('button', { name: '复制页面地址' }))

  const copiedText = await navigator.clipboard.readText()
  expect(copiedText).toBe(window.location.href)
  expect(copiedText).not.toContain(sensitiveErrorMessage)
  expect(await screen.findByText('页面地址已复制')).toHaveAttribute(
    'aria-live',
    'polite',
  )
})

test('hides the copy action when the Clipboard API is unavailable', () => {
  const restoreClipboard = replaceClipboard(undefined)

  try {
    render(
      <ErrorBoundary onReload={vi.fn()}>
        <BrokenView />
      </ErrorBoundary>,
      { onCaughtError: reactRootOptions.onCaughtError },
    )

    expect(
      screen.queryByRole('button', { name: '复制页面地址' }),
    ).not.toBeInTheDocument()
  } finally {
    restoreClipboard()
  }
})

test('announces a safe message when copying the page address is rejected', async () => {
  const user = userEvent.setup()
  const clipboardError = 'private clipboard rejection detail'
  const restoreClipboard = replaceClipboard({
    writeText: vi.fn().mockRejectedValue(new Error(clipboardError)),
  })

  try {
    render(
      <ErrorBoundary onReload={vi.fn()}>
        <BrokenView />
      </ErrorBoundary>,
      { onCaughtError: reactRootOptions.onCaughtError },
    )

    await user.click(screen.getByRole('button', { name: '复制页面地址' }))

    expect(await screen.findByText('无法复制页面地址')).toHaveAttribute(
      'aria-live',
      'polite',
    )
    expect(screen.queryByText(clipboardError)).not.toBeInTheDocument()
  } finally {
    restoreClipboard()
  }
})

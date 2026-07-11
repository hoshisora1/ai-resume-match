import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'

import { ErrorBoundary } from './ErrorBoundary'

const sensitiveErrorMessage = 'resume text must stay private'

function BrokenView(): never {
  throw new Error(sensitiveErrorMessage)
}

test('renders a restrained fallback without exposing the render error', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  const onReload = vi.fn()
  const user = userEvent.setup()

  try {
    render(
      <ErrorBoundary onReload={onReload}>
        <BrokenView />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('页面暂时无法显示')
    expect(screen.queryByText(sensitiveErrorMessage)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '刷新页面' })).toBeVisible()
    expect(screen.getByRole('button', { name: '复制页面地址' })).toHaveAttribute(
      'title',
      '复制页面地址',
    )

    await user.click(screen.getByRole('button', { name: '刷新页面' }))

    expect(onReload).toHaveBeenCalledTimes(1)
  } finally {
    consoleError.mockRestore()
  }
})

test('copies only the current page address from the fallback action', async () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
  const user = userEvent.setup()

  try {
    render(
      <ErrorBoundary onReload={vi.fn()}>
        <BrokenView />
      </ErrorBoundary>,
    )

    await user.click(screen.getByRole('button', { name: '复制页面地址' }))

    const copiedText = await navigator.clipboard.readText()
    expect(copiedText).toBe(window.location.href)
    expect(copiedText).not.toContain(sensitiveErrorMessage)
  } finally {
    consoleError.mockRestore()
  }
})

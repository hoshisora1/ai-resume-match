import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'

import { ErrorBoundary } from './ErrorBoundary'
import { reactRootOptions } from './reactRootOptions'

const sensitiveErrorMessage = 'resume text must stay private'

function BrokenView(): never {
  throw new Error(sensitiveErrorMessage)
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

  expect(screen.getByRole('alert')).toHaveTextContent('页面暂时无法显示')
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
})

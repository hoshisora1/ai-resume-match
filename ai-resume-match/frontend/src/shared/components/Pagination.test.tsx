import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'

import { Pagination } from './Pagination'

test('disables the previous action on the first page and never emits a negative page', async () => {
  const onPageChange = vi.fn()
  const user = userEvent.setup()

  render(<Pagination page={0} totalPages={3} onPageChange={onPageChange} />)

  const previous = screen.getByRole('button', { name: '上一页' })
  const next = screen.getByRole('button', { name: '下一页' })
  expect(previous).toBeDisabled()
  expect(previous).toHaveAttribute('title', '上一页')
  expect(next).toBeEnabled()
  expect(screen.getByText('第 1 / 3 页')).toBeVisible()

  await user.click(previous)
  await user.click(next)

  expect(onPageChange).toHaveBeenCalledTimes(1)
  expect(onPageChange).toHaveBeenCalledWith(1, { replace: true })
})

test('disables the next action on the last page and never emits a page past the end', async () => {
  const onPageChange = vi.fn()
  const user = userEvent.setup()

  render(<Pagination page={2} totalPages={3} onPageChange={onPageChange} />)

  const previous = screen.getByRole('button', { name: '上一页' })
  const next = screen.getByRole('button', { name: '下一页' })
  expect(previous).toBeEnabled()
  expect(next).toBeDisabled()
  expect(next).toHaveAttribute('title', '下一页')
  expect(screen.getByText('第 3 / 3 页')).toBeVisible()

  await user.click(next)
  await user.click(previous)

  expect(onPageChange).toHaveBeenCalledTimes(1)
  expect(onPageChange).toHaveBeenCalledWith(1, { replace: true })
})

test('keeps both actions disabled when there are no pages', async () => {
  const onPageChange = vi.fn()
  const user = userEvent.setup()

  render(<Pagination page={0} totalPages={0} onPageChange={onPageChange} />)

  const previous = screen.getByRole('button', { name: '上一页' })
  const next = screen.getByRole('button', { name: '下一页' })
  expect(previous).toBeDisabled()
  expect(next).toBeDisabled()
  expect(screen.getByText('第 0 / 0 页')).toBeVisible()

  await user.click(previous)
  await user.click(next)

  expect(onPageChange).not.toHaveBeenCalled()
})

test('synchronizes a negative controlled page to the first page', async () => {
  const onPageChange = vi.fn()

  render(<Pagination page={-4} totalPages={3} onPageChange={onPageChange} />)

  expect(screen.getByText('第 1 / 3 页')).toBeVisible()
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()

  await waitFor(() => {
    expect(onPageChange).toHaveBeenCalledTimes(1)
    expect(onPageChange).toHaveBeenCalledWith(0, { replace: true })
  })
})

test('synchronizes a page past the end before accepting previous-page input', async () => {
  const onPageChange = vi.fn()
  const user = userEvent.setup()

  const { rerender } = render(
    <Pagination page={99} totalPages={3} onPageChange={onPageChange} />,
  )

  expect(screen.getByText('第 3 / 3 页')).toBeVisible()
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()

  await waitFor(() => {
    expect(onPageChange).toHaveBeenCalledTimes(1)
    expect(onPageChange).toHaveBeenCalledWith(2, { replace: true })
  })

  rerender(<Pagination page={2} totalPages={3} onPageChange={onPageChange} />)

  expect(screen.getByRole('button', { name: '上一页' })).toBeEnabled()
  await user.click(screen.getByRole('button', { name: '上一页' }))

  expect(onPageChange).toHaveBeenCalledTimes(2)
  expect(onPageChange).toHaveBeenLastCalledWith(1, { replace: true })
})

test('shows a stable single page with both actions disabled', () => {
  render(<Pagination page={8} totalPages={1} onPageChange={vi.fn()} />)

  expect(screen.getByText('第 1 / 1 页')).toBeVisible()
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
})

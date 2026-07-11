import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test, vi } from 'vitest'

import { AsyncState } from './AsyncState'

test('announces a stable loading state', () => {
  render(<AsyncState state="loading" label="正在读取分析记录" />)

  const state = screen.getByRole('status')
  expect(state).toHaveAttribute('aria-busy', 'true')
  expect(state).toHaveAttribute('data-state', 'loading')
  expect(state).toHaveTextContent('正在读取分析记录')
})

test('renders an empty state with clear semantics', () => {
  render(
    <AsyncState
      state="empty"
      title="暂无分析记录"
      description="创建第一项分析后，记录会显示在这里。"
    />,
  )

  const state = screen.getByRole('status')
  expect(state).toHaveAttribute('data-state', 'empty')
  expect(state).toHaveTextContent('暂无分析记录')
  expect(state).toHaveTextContent('创建第一项分析后，记录会显示在这里。')
})

test('renders an error state and invokes its optional retry action', async () => {
  const onRetry = vi.fn()
  const user = userEvent.setup()

  render(
    <AsyncState
      state="error"
      title="分析记录加载失败"
      description="请检查连接后重试。"
      onRetry={onRetry}
    />,
  )

  const state = screen.getByRole('alert')
  expect(state).toHaveAttribute('data-state', 'error')
  expect(state).toHaveTextContent('分析记录加载失败')

  await user.click(screen.getByRole('button', { name: '重试' }))

  expect(onRetry).toHaveBeenCalledTimes(1)
})

test('renders content without replacing it with a status message', () => {
  render(
    <AsyncState state="content">
      <p>分析内容</p>
    </AsyncState>,
  )

  expect(screen.getByText('分析内容').parentElement).toHaveAttribute(
    'data-state',
    'content',
  )
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

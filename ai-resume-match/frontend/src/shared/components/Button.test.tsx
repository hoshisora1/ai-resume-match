import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, test, vi } from 'vitest'

import { Button } from './Button'

describe('Button', () => {
  test.each(['primary', 'secondary', 'danger'] as const)(
    'renders the %s visual variant',
    (variant) => {
      render(<Button variant={variant}>{variant}</Button>)

      expect(screen.getByRole('button', { name: variant })).toHaveAttribute(
        'data-variant',
        variant,
      )
    },
  )

  test('keeps its accessible name and blocks repeat clicks while loading', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()

    render(
      <Button loading onClick={onClick}>
        提交分析
      </Button>,
    )

    const button = screen.getByRole('button', { name: '提交分析' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByText('提交分析')).toHaveClass('button__content')

    await user.click(button)

    expect(onClick).not.toHaveBeenCalled()
  })

  test('honors the disabled state', async () => {
    const onClick = vi.fn()
    const user = userEvent.setup()

    render(<Button disabled onClick={onClick}>删除</Button>)

    const button = screen.getByRole('button', { name: '删除' })
    expect(button).toBeDisabled()

    await user.click(button)

    expect(onClick).not.toHaveBeenCalled()
  })
})

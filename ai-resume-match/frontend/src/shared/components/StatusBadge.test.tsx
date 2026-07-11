import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'

import type { AnalysisStatus } from '../api/schemas'
import { StatusBadge } from './StatusBadge'

const statusLabels: ReadonlyArray<[AnalysisStatus, string]> = [
  ['PENDING', '待处理'],
  ['RUNNING', '分析中'],
  ['SUCCESS', '已完成'],
  ['FAILED_RETRYABLE', '可重试失败'],
  ['FAILED_FINAL', '最终失败'],
  ['CANCELLED', '已取消'],
  ['FAILED', '分析失败'],
]

test.each(statusLabels)('maps %s to a Chinese label and non-color cue', (status, label) => {
  render(<StatusBadge status={status} />)

  const badge = screen.getByText(label).closest('[data-status]')
  expect(badge).toHaveAttribute('data-status', status)
  expect(badge?.querySelector('[aria-hidden="true"]')).toHaveTextContent(/\S/)
})

test('identifies a retryable failure with its backend status', () => {
  render(<StatusBadge status="FAILED_RETRYABLE" />)

  expect(screen.getByText('可重试失败')).toHaveAttribute(
    'data-status',
    'FAILED_RETRYABLE',
  )
})

import type { AnalysisStatus } from '../../shared/api/schemas'

export const analysisStatusOptions = [
  { label: '待处理', value: 'PENDING' },
  { label: '分析中', value: 'RUNNING' },
  { label: '已完成', value: 'SUCCESS' },
  { label: '可重试失败', value: 'FAILED_RETRYABLE' },
  { label: '最终失败', value: 'FAILED_FINAL' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '分析失败', value: 'FAILED' },
] as const satisfies ReadonlyArray<{
  label: string
  value: AnalysisStatus
}>

const analysisStatuses = new Set<AnalysisStatus>(
  analysisStatusOptions.map(({ value }) => value),
)

export function parseAnalysisStatus(value: string | null) {
  if (value !== null && analysisStatuses.has(value as AnalysisStatus)) {
    return value as AnalysisStatus
  }

  return undefined
}

export function formatAnalysisScore(score: number | null) {
  return score === null ? '—' : score.toFixed(1)
}

export function formatAnalysisDate(value: string | null) {
  if (value === null) {
    return '—'
  }

  const localDateTime =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value)
  if (localDateTime === null) {
    return '—'
  }

  const [, year, month, day, hour, minute] = localDateTime
  return `${year}-${month}-${day} ${hour}:${minute}`
}

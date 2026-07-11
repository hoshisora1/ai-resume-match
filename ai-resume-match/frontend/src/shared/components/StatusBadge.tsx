import type { AnalysisStatus } from '../api/schemas'

interface StatusPresentation {
  cue: string
  label: string
}

const statusPresentations: Record<AnalysisStatus, StatusPresentation> = {
  PENDING: { cue: '○', label: '待处理' },
  RUNNING: { cue: '···', label: '分析中' },
  SUCCESS: { cue: '✓', label: '已完成' },
  FAILED_RETRYABLE: { cue: '!', label: '可重试失败' },
  FAILED_FINAL: { cue: '×', label: '最终失败' },
  CANCELLED: { cue: '—', label: '已取消' },
  FAILED: { cue: '×', label: '分析失败' },
}

interface StatusBadgeProps {
  status: AnalysisStatus
}

export function StatusBadge({ status }: StatusBadgeProps) {
  const presentation = statusPresentations[status]

  return (
    <span className="status-badge" data-status={status}>
      <span aria-hidden="true" className="status-badge__cue">
        {presentation.cue}
      </span>
      {presentation.label}
    </span>
  )
}

import { Copy } from 'lucide-react'
import { useState } from 'react'

interface CopyReferenceProps {
  label: '任务 ID' | '请求 ID'
  value: string
}

type CopyStatus = 'idle' | 'success' | 'unavailable' | 'error'

interface CopyResult {
  key: string
  status: CopyStatus
}

export function CopyReference({ label, value }: CopyReferenceProps) {
  const referenceKey = `${label}:${value}`
  const [copyResult, setCopyResult] = useState<CopyResult>({
    key: referenceKey,
    status: 'idle',
  })
  const copyStatus =
    copyResult.key === referenceKey ? copyResult.status : 'idle'

  const copyValue = async () => {
    const clipboard = navigator.clipboard
    if (typeof clipboard?.writeText !== 'function') {
      setCopyResult({ key: referenceKey, status: 'unavailable' })
      return
    }

    try {
      await clipboard.writeText(value)
      setCopyResult({ key: referenceKey, status: 'success' })
    } catch {
      setCopyResult({ key: referenceKey, status: 'error' })
    }
  }

  const feedback =
    copyStatus === 'success'
      ? `${label} 已复制`
      : copyStatus === 'unavailable'
        ? `当前环境无法复制${label}`
        : copyStatus === 'error'
          ? `复制${label} 失败`
          : ''

  return (
    <div className="copy-reference">
      <span className="copy-reference__label">{label}</span>
      <code className="copy-reference__value">{value}</code>
      <button
        aria-label={`复制${label}`}
        className="icon-button copy-reference__button"
        onClick={() => void copyValue()}
        title={`复制${label}`}
        type="button"
      >
        <Copy aria-hidden="true" size={17} />
      </button>
      <span
        aria-live="polite"
        className="copy-reference__feedback"
      >
        {feedback}
      </span>
    </div>
  )
}

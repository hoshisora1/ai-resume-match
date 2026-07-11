import { AlertTriangle, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

import { Button } from './Button'

interface AsyncStateBaseProps {
  className?: string
}

type AsyncStateProps = AsyncStateBaseProps &
  (
    | {
        state: 'loading'
        label?: string
      }
    | {
        state: 'empty'
        description?: string
        title?: string
      }
    | {
        state: 'error'
        description?: string
        onRetry?: () => void
        title?: string
      }
    | {
        state: 'content'
        children: ReactNode
      }
  )

function getClassName(className: string | undefined) {
  return ['async-state', className].filter(Boolean).join(' ')
}

export function AsyncState(props: AsyncStateProps) {
  const className = getClassName(props.className)

  if (props.state === 'loading') {
    return (
      <div
        aria-busy="true"
        aria-live="polite"
        className={className}
        data-state="loading"
        role="status"
      >
        <span aria-hidden="true" className="async-state__loader" />
        <p className="async-state__title">{props.label ?? '正在加载'}</p>
      </div>
    )
  }

  if (props.state === 'empty') {
    return (
      <div
        aria-live="polite"
        className={className}
        data-state="empty"
        role="status"
      >
        <p className="async-state__title">{props.title ?? '暂无数据'}</p>
        {props.description ? (
          <p className="async-state__description">{props.description}</p>
        ) : null}
      </div>
    )
  }

  if (props.state === 'error') {
    return (
      <div className={className} data-state="error" role="alert">
        <AlertTriangle
          aria-hidden="true"
          className="async-state__icon"
          size={24}
        />
        <p className="async-state__title">
          {props.title ?? '暂时无法加载'}
        </p>
        {props.description ? (
          <p className="async-state__description">{props.description}</p>
        ) : null}
        {props.onRetry ? (
          <Button onClick={props.onRetry} variant="secondary">
            <RefreshCw aria-hidden="true" size={16} />
            <span>重试</span>
          </Button>
        ) : null}
      </div>
    )
  }

  return (
    <div className={className} data-state="content">
      {props.children}
    </div>
  )
}

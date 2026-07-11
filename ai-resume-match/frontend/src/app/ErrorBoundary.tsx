import { AlertTriangle, Copy, RefreshCw } from 'lucide-react'
import { Component, type ReactNode } from 'react'

import { Button } from '../shared/components/Button'

interface ErrorBoundaryProps {
  children: ReactNode
  onReload?: () => void
}

interface ErrorBoundaryState {
  hasError: boolean
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true }
  }

  private readonly reloadPage = () => {
    if (this.props.onReload) {
      this.props.onReload()
      return
    }

    window.location.reload()
  }

  private readonly copyPageAddress = () => {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(window.location.href).catch(() => undefined)
    }
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children
    }

    return (
      <main className="error-boundary" role="alert">
        <div className="error-boundary__content">
          <AlertTriangle
            aria-hidden="true"
            className="error-boundary__icon"
            size={30}
          />
          <h1>页面暂时无法显示</h1>
          <p>界面遇到意外。刷新页面后可以继续操作。</p>
          <div className="error-boundary__actions">
            <Button onClick={this.reloadPage}>
              <RefreshCw aria-hidden="true" size={17} />
              <span>刷新页面</span>
            </Button>
            <button
              aria-label="复制页面地址"
              className="icon-button"
              onClick={this.copyPageAddress}
              title="复制页面地址"
              type="button"
            >
              <Copy aria-hidden="true" size={18} />
            </button>
          </div>
        </div>
      </main>
    )
  }
}

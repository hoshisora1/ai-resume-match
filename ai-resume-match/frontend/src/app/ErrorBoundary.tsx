import { AlertTriangle, Copy, RefreshCw } from 'lucide-react'
import { Component, type ReactNode } from 'react'

import { Button } from '../shared/components/Button'

interface ErrorBoundaryProps {
  children: ReactNode
  onReload?: () => void
}

interface ErrorBoundaryState {
  copyStatus: 'idle' | 'success' | 'error'
  hasError: boolean
}

export class ErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { copyStatus: 'idle', hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { copyStatus: 'idle', hasError: true }
  }

  private readonly reloadPage = () => {
    if (this.props.onReload) {
      this.props.onReload()
      return
    }

    window.location.reload()
  }

  private readonly copyPageAddress = async () => {
    const clipboard = navigator.clipboard
    if (typeof clipboard?.writeText !== 'function') {
      return
    }

    try {
      await clipboard.writeText(window.location.href)
      this.setState({ copyStatus: 'success' })
    } catch {
      this.setState({ copyStatus: 'error' })
    }
  }

  render() {
    if (!this.state.hasError) {
      return this.props.children
    }

    const canCopyPageAddress =
      typeof navigator.clipboard?.writeText === 'function'
    const copyFeedback =
      this.state.copyStatus === 'success'
        ? '页面地址已复制'
        : this.state.copyStatus === 'error'
          ? '无法复制页面地址'
          : ''

    return (
      <main
        className="error-boundary"
        id="main-content"
        tabIndex={-1}
      >
        <div className="error-boundary__content">
          <div className="error-boundary__alert" role="alert">
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
              {canCopyPageAddress ? (
                <button
                  aria-label="复制页面地址"
                  className="icon-button"
                  onClick={() => void this.copyPageAddress()}
                  title="复制页面地址"
                  type="button"
                >
                  <Copy aria-hidden="true" size={18} />
                </button>
              ) : null}
            </div>
          </div>
          <p
            aria-live="polite"
            className="error-boundary__copy-status"
          >
            {copyFeedback}
          </p>
        </div>
      </main>
    )
  }
}

import { useQuery } from '@tanstack/react-query'
import { History, LayoutDashboard, Plus, RefreshCw } from 'lucide-react'
import { useEffect, useRef } from 'react'
import {
  Link,
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
} from 'react-router'

import { Button } from '../shared/components/Button'
import { backendHealthQueryOptions } from './backendHealthQuery'

const navigationItems = [
  { end: true, icon: LayoutDashboard, label: '总览', to: '/' },
  { end: true, icon: History, label: '分析记录', to: '/analyses' },
  { end: true, icon: Plus, label: '新建分析', to: '/analyses/new' },
] as const

export function AppShell() {
  const location = useLocation()
  const navigate = useNavigate()
  const mainRef = useRef<HTMLElement>(null)
  const previousPathname = useRef(location.pathname)
  const healthQuery = useQuery(backendHealthQueryOptions)
  const isChecking = healthQuery.isPending
  const isConnected =
    healthQuery.isSuccess && healthQuery.data.status.toUpperCase() === 'UP'
  const healthLabel = isChecking
    ? '正在检查 API'
    : isConnected
      ? 'API 已连接'
      : 'API 暂不可用'
  const healthState = isChecking
    ? 'checking'
    : isConnected
      ? 'connected'
      : 'unavailable'
  const lastCheckedAt = Math.max(
    healthQuery.dataUpdatedAt,
    healthQuery.errorUpdatedAt,
  )
  const healthTitle =
    lastCheckedAt > 0
      ? `最后检查：${new Intl.DateTimeFormat('zh-CN', {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          hour12: false,
        }).format(lastCheckedAt)}`
      : healthLabel
  const isNewAnalysisPath =
    location.pathname === '/analyses/new' ||
    location.pathname.startsWith('/analyses/new/')

  useEffect(() => {
    if (previousPathname.current === location.pathname) {
      return
    }

    previousPathname.current = location.pathname
    const main = mainRef.current
    const activeElement = document.activeElement

    if (!main || (activeElement && main.contains(activeElement))) {
      return
    }

    const hasNoActiveControl =
      !activeElement ||
      activeElement === document.body ||
      activeElement === document.documentElement
    const focusRemainsOnRouteControl =
      activeElement instanceof HTMLElement &&
      activeElement.dataset.shellRouteControl === 'true'

    if (hasNoActiveControl || focusRemainsOnRouteControl) {
      main.focus()
    }
  }, [location.pathname])

  return (
    <div className="app-shell">
      <a
        className="skip-link"
        href="#main-content"
        onClick={(event) => {
          event.preventDefault()
          mainRef.current?.focus()
        }}
      >
        跳到主要内容
      </a>
      <aside className="app-sidebar">
        <Link className="brand-link" data-shell-route-control="true" to="/">
          <span className="brand-mark" aria-hidden="true">
            M
          </span>
          <span className="brand-name">MatchLab</span>
        </Link>
        <nav aria-label="主导航" className="app-nav">
          {navigationItems.map(({ end, icon: Icon, label, to }) => (
            <NavLink
              className={({ isActive }) =>
                `app-nav__link${isActive ? ' app-nav__link--active' : ''}`
              }
              data-shell-route-control="true"
              end={to === '/analyses' ? isNewAnalysisPath : end}
              key={to}
              to={to}
            >
              <Icon aria-hidden="true" size={18} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
      </aside>

      <header className="app-topbar">
        <div className="backend-health" data-health={healthState} title={healthTitle}>
          <span
            aria-live="polite"
            className="backend-health__summary"
            role="status"
          >
            <span aria-hidden="true" className="backend-health__cue" />
            <span>{healthLabel}</span>
          </span>
          {!isChecking && !isConnected ? (
            <button
              aria-busy={healthQuery.isFetching || undefined}
              aria-label="重新检查 API"
              className="icon-button backend-health__retry"
              disabled={healthQuery.isFetching}
              onClick={() => void healthQuery.refetch()}
              title="重新检查 API"
              type="button"
            >
              <RefreshCw
                aria-hidden="true"
                className={healthQuery.isFetching ? 'is-spinning' : undefined}
                size={16}
              />
            </button>
          ) : null}
        </div>
        <Button
          className="app-topbar__action"
          data-shell-route-control="true"
          onClick={() => navigate('/analyses/new')}
        >
          <Plus aria-hidden="true" size={17} />
          <span>新建分析</span>
        </Button>
      </header>

      <main
        className="app-main"
        id="main-content"
        ref={mainRef}
        tabIndex={-1}
      >
        <div className="app-content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}

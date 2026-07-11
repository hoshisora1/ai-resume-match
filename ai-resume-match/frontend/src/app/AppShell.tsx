import { useQuery } from '@tanstack/react-query'
import { History, LayoutDashboard, Plus } from 'lucide-react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'

import { Button } from '../shared/components/Button'
import { backendHealthQueryOptions } from './backendHealthQuery'

const navigationItems = [
  { end: true, icon: LayoutDashboard, label: '总览', to: '/' },
  { end: true, icon: History, label: '分析记录', to: '/analyses' },
  { end: true, icon: Plus, label: '新建分析', to: '/analyses/new' },
] as const

export function AppShell() {
  const navigate = useNavigate()
  const healthQuery = useQuery(backendHealthQueryOptions)
  const isConnected =
    healthQuery.isSuccess && healthQuery.data.status.toUpperCase() === 'UP'
  const healthLabel = isConnected ? 'API 已连接' : 'API 暂不可用'

  return (
    <div className="app-shell">
      <aside className="app-sidebar">
        <Link className="brand-link" to="/">
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
              end={end}
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
        <div
          aria-live="polite"
          className="backend-health"
          data-health={isConnected ? 'connected' : 'unavailable'}
          role="status"
        >
          <span aria-hidden="true" className="backend-health__cue" />
          <span>{healthLabel}</span>
        </div>
        <Button
          className="app-topbar__action"
          onClick={() => navigate('/analyses/new')}
        >
          <Plus aria-hidden="true" size={17} />
          <span>新建分析</span>
        </Button>
      </header>

      <main className="app-main">
        <div className="app-content">
          <Outlet />
        </div>
      </main>
    </div>
  )
}

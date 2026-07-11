import { Link, Outlet } from 'react-router'

interface PlaceholderPageProps {
  title: string
}

export function AppShell() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <Link className="brand-link" to="/">
          MatchLab
        </Link>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}

export function PlaceholderPage({ title }: PlaceholderPageProps) {
  return (
    <section aria-labelledby="page-title" className="placeholder-page">
      <h1 id="page-title">{title}</h1>
    </section>
  )
}

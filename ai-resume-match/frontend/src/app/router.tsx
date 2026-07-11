import { Link, Outlet, RouterProvider, createBrowserRouter } from 'react-router'

interface PlaceholderPageProps {
  title: string
}

function AppShell() {
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

function PlaceholderPage({ title }: PlaceholderPageProps) {
  return (
    <section aria-labelledby="page-title" className="placeholder-page">
      <h1 id="page-title">{title}</h1>
    </section>
  )
}

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <PlaceholderPage title="分析总览" /> },
      { path: 'analyses', element: <PlaceholderPage title="分析历史" /> },
      { path: 'analyses/new', element: <PlaceholderPage title="新建分析" /> },
      { path: 'analyses/:taskId', element: <PlaceholderPage title="分析详情" /> },
    ],
  },
])

export function AppRouter() {
  return <RouterProvider router={router} />
}

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from './app/App'
import { createQueryClient } from './app/queryClient'
import { createAppBrowserRouter } from './app/router'
import './styles/tokens.css'
import './styles/global.css'

const rootElement = document.getElementById('root')

if (rootElement === null) {
  throw new Error('Root element not found')
}

const queryClient = createQueryClient()
const router = createAppBrowserRouter()

createRoot(rootElement).render(
  <StrictMode>
    <App queryClient={queryClient} router={router} />
  </StrictMode>,
)

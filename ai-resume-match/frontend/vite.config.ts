import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.API_PROXY_TARGET || 'http://localhost:8080'
  const headers = env.API_TOKEN ? { 'X-API-Token': env.API_TOKEN } : undefined

  return {
    plugins: [react()],
    server: {
      proxy: {
        '/api': { target, changeOrigin: true, headers },
        '/backend-health': {
          target,
          changeOrigin: true,
          rewrite: () => '/actuator/health/readiness',
        },
      },
    },
  }
})

import { readdir, readFile, mkdtemp, rm } from 'node:fs/promises'
import { createServer as createHttpServer, type Server } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cwd, env } from 'node:process'
import { build, createServer, type ViteDevServer } from 'vite'
import { expect, test, vi } from 'vitest'

import { runCleanupSteps } from './resourceCleanup'

const frontendRoot = cwd()
const viteConfigPath = join(frontendRoot, 'vite.config.ts')
const cleanupTimeoutMs = 5_000

async function listenOnEphemeralPort(server: Server) {
  await new Promise<void>((resolve, reject) => {
    const handleError = (error: Error) => {
      reject(error)
    }

    server.once('error', handleError)
    server.listen(0, '127.0.0.1', () => {
      server.off('error', handleError)
      resolve()
    })
  })

  const address = server.address()

  if (address === null || typeof address === 'string') {
    throw new Error('Mock upstream did not bind to a TCP port')
  }

  return address.port
}

async function closeHttpServer(server: Server) {
  if (!server.listening) {
    return
  }

  server.closeAllConnections()
  await new Promise<void>((resolve, reject) => {
    server.close((error) => {
      if (error) {
        reject(error)
      } else {
        resolve()
      }
    })
  })
}

function viteServerOrigin(server: ViteDevServer) {
  const address = server.httpServer?.address()

  if (address === undefined || address === null || typeof address === 'string') {
    throw new Error('Vite did not bind to a TCP port')
  }

  return `http://127.0.0.1:${address.port}`
}

async function readBuildOutput(directory: string): Promise<string> {
  const entries = await readdir(directory, { withFileTypes: true })
  const contents = await Promise.all(
    entries.map(async (entry) => {
      const path = join(directory, entry.name)

      return entry.isDirectory() ? readBuildOutput(path) : readFile(path, 'utf8')
    }),
  )

  return contents.join('\n')
}

function restoreEnvironment(name: 'API_TOKEN' | 'API_PROXY_TARGET', value: string | undefined) {
  if (value === undefined) {
    delete env[name]
  } else {
    env[name] = value
  }
}

interface ProxyTestResources {
  previousToken: string | undefined
  previousTarget: string | undefined
  closeVite: () => Promise<void> | void
  closeUpstream: () => Promise<void> | void
  removeBuildDirectory: () => Promise<void> | void
}

async function cleanupProxyTestResources(
  resources: ProxyTestResources,
  timeoutMs = cleanupTimeoutMs,
) {
  restoreEnvironment('API_TOKEN', resources.previousToken)
  restoreEnvironment('API_PROXY_TARGET', resources.previousTarget)
  await runCleanupSteps(
    [
      {
        label: 'Vite server',
        run: resources.closeVite,
      },
      {
        label: 'mock upstream',
        run: resources.closeUpstream,
      },
      {
        label: 'temporary build directory',
        run: resources.removeBuildDirectory,
      },
    ],
    timeoutMs,
  )
}

test('restores env and attempts all resources when Vite close never settles', async () => {
  vi.useFakeTimers()
  const previousToken = env.API_TOKEN
  const previousTarget = env.API_PROXY_TARGET
  let upstreamCloseAttempted = false
  let buildRemovalAttempted = false

  env.API_TOKEN = 'temporary-lifecycle-token'
  env.API_PROXY_TARGET = 'http://temporary-lifecycle-target.invalid'

  try {
    const cleanup = cleanupProxyTestResources(
      {
        previousToken,
        previousTarget,
        closeVite: () => new Promise<void>(() => undefined),
        closeUpstream: () => {
          upstreamCloseAttempted = true
        },
        removeBuildDirectory: () => {
          buildRemovalAttempted = true
        },
      },
      100,
    )
    const settlement = cleanup.then(
      () => null,
      (error: unknown) => error,
    )

    expect(env.API_TOKEN).toBe(previousToken)
    expect(env.API_PROXY_TARGET).toBe(previousTarget)

    await vi.advanceTimersByTimeAsync(100)
    const cleanupError = await settlement

    expect(cleanupError).toBeInstanceOf(AggregateError)
    expect(upstreamCloseAttempted).toBe(true)
    expect(buildRemovalAttempted).toBe(true)
  } finally {
    restoreEnvironment('API_TOKEN', previousToken)
    restoreEnvironment('API_PROXY_TARGET', previousTarget)
    vi.useRealTimers()
  }
})

test('proxies server-only credentials and keeps them out of the client build', async () => {
  const token = 'task5-proxy-contract-token'
  const previousToken = env.API_TOKEN
  const previousTarget = env.API_PROXY_TARGET
  const upstream = createHttpServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(
      JSON.stringify({
        host: request.headers.host,
        path: request.url,
        token: request.headers['x-api-token'] ?? null,
      }),
    )
  })
  let viteServer: ViteDevServer | undefined
  let buildDirectory: string | undefined

  try {
    const upstreamPort = await listenOnEphemeralPort(upstream)
    const proxyTarget = `http://127.0.0.1:${upstreamPort}`
    env.API_TOKEN = token
    env.API_PROXY_TARGET = proxyTarget

    viteServer = await createServer({
      configFile: viteConfigPath,
      root: frontendRoot,
      logLevel: 'silent',
      server: {
        host: '127.0.0.1',
        port: 0,
        strictPort: false,
      },
    })
    await viteServer.listen()

    const origin = viteServerOrigin(viteServer)
    const apiResponse = await fetch(`${origin}/api/probe`, {
      signal: AbortSignal.timeout(5_000),
    })
    const apiObservation: unknown = await apiResponse.json()

    expect(apiResponse.ok).toBe(true)
    expect(apiObservation).toEqual({
      host: `127.0.0.1:${upstreamPort}`,
      path: '/api/probe',
      token,
    })

    const healthResponse = await fetch(`${origin}/backend-health`, {
      signal: AbortSignal.timeout(5_000),
    })
    const healthObservation: unknown = await healthResponse.json()

    expect(healthResponse.ok).toBe(true)
    expect(healthObservation).toEqual({
      host: `127.0.0.1:${upstreamPort}`,
      path: '/actuator/health/readiness',
      token: null,
    })

    buildDirectory = await mkdtemp(join(tmpdir(), 'matchlab-vite-build-'))
    await build({
      configFile: viteConfigPath,
      root: frontendRoot,
      logLevel: 'silent',
      build: {
        emptyOutDir: true,
        outDir: buildDirectory,
      },
    })

    const clientOutput = await readBuildOutput(buildDirectory)

    expect(clientOutput).not.toContain(token)
    expect(clientOutput).not.toContain(proxyTarget)
    expect(clientOutput).not.toContain('X-API-Token')
    expect(clientOutput).not.toContain('API_PROXY_TARGET')
  } finally {
    await cleanupProxyTestResources({
      previousToken,
      previousTarget,
      closeVite: () => viteServer?.close(),
      closeUpstream: () => closeHttpServer(upstream),
      removeBuildDirectory: () =>
        buildDirectory === undefined
          ? undefined
          : rm(buildDirectory, { force: true, recursive: true }),
    })
  }
}, 30_000)

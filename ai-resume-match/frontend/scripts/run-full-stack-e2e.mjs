import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDirectory = fileURLToPath(new URL('..', import.meta.url))
const projectDirectory = fileURLToPath(new URL('../..', import.meta.url))
const composeProject = 'ai-resume-match-frontend-e2e'
const composeFiles = [
  '--env-file',
  '.env.example',
  '-f',
  'docker-compose.yml',
  '-f',
  'docker-compose.e2e.yml',
]
const testEnvironment = {
  ...process.env,
  APP_PORT: '18081',
  FRONTEND_PORT: '18080',
  MYSQL_PORT: '13308',
  REDIS_PORT: '16381',
  RABBITMQ_AMQP_PORT: '15678',
  RABBITMQ_MANAGEMENT_PORT: '15679',
  API_TOKEN: 'e2e-browser-token',
  AI_API_KEY: 'e2e-mock-key',
  FULL_STACK_E2E: 'true',
  PLAYWRIGHT_BASE_URL: 'http://127.0.0.1:18080',
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd ?? projectDirectory,
      env: testEnvironment,
      stdio: 'inherit',
      windowsHide: true,
    })

    child.once('error', reject)
    child.once('exit', (code, signal) => {
      if (code === 0) {
        resolve()
        return
      }

      const reason = signal ? `signal ${signal}` : `exit code ${code ?? 1}`
      reject(new Error(`${command} failed with ${reason}`))
    })
  })
}

const docker = process.platform === 'win32' ? 'docker.exe' : 'docker'
const composeArgs = ['compose', '-p', composeProject, ...composeFiles]
const playwrightCli = path.join(
  frontendDirectory,
  'node_modules',
  '@playwright',
  'test',
  'cli.js',
)
let exitCode = 0

try {
  await run(docker, [
    ...composeArgs,
    'up',
    '-d',
    '--build',
    '--wait',
    '--wait-timeout',
    '300',
  ])
  await run(
    process.execPath,
    [
      playwrightCli,
      'test',
      'e2e/full-stack.spec.ts',
      '--project=chromium',
    ],
    { cwd: frontendDirectory },
  )
} catch (error) {
  exitCode = 1
  console.error(error instanceof Error ? error.message : String(error))
} finally {
  try {
    await run(docker, [...composeArgs, 'down', '-v', '--remove-orphans'])
  } catch (error) {
    exitCode = 1
    console.error(
      `Full-stack E2E cleanup failed: ${
        error instanceof Error ? error.message : String(error)
      }`,
    )
  }
}

process.exitCode = exitCode

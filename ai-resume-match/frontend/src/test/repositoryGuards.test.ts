import { spawnSync } from 'node:child_process'
import { cwd } from 'node:process'
import { describe, expect, test } from 'vitest'

const frontendRoot = cwd()

function gitCheckIgnore(relativePath: string) {
  const result = spawnSync(
    'git',
    ['check-ignore', '--no-index', '--quiet', '--', relativePath],
    {
      cwd: frontendRoot,
      encoding: 'utf8',
      windowsHide: true,
    },
  )

  if (result.error) {
    throw result.error
  }

  return result
}

describe('frontend environment-file protection', () => {
  test.each([
    '.env',
    '.env.local',
    '.env.development',
    '.env.development.local',
    '.env.production.local',
  ])('ignores %s', (relativePath) => {
    const result = gitCheckIgnore(relativePath)

    expect(result.status, result.stderr).toBe(0)
  })

  test('allows the documented example environment file', () => {
    const result = gitCheckIgnore('.env.example')

    expect(result.status, result.stderr).toBe(1)
  })
})

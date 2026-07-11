import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'
import { expect, test } from 'vitest'

const tokensCss = readFileSync(resolve(cwd(), 'src/styles/tokens.css'), 'utf8')
const globalCss = readFileSync(resolve(cwd(), 'src/styles/global.css'), 'utf8')

function readColorToken(name: string) {
  const match = tokensCss.match(
    new RegExp(`--${name}:\\s*(#[0-9a-f]{6})`, 'i'),
  )
  expect(match, `Missing --${name} token`).not.toBeNull()
  return match?.[1] ?? '#000000'
}

function relativeLuminance(hexColor: string) {
  const channels = hexColor
    .slice(1)
    .match(/.{2}/g)
    ?.map((channel) => Number.parseInt(channel, 16) / 255)
    .map((channel) =>
      channel <= 0.04045
        ? channel / 12.92
        : ((channel + 0.055) / 1.055) ** 2.4,
    )

  if (channels?.length !== 3) {
    throw new Error('Expected a six-digit hex color')
  }

  return channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722
}

function contrastRatio(foreground: string, background: string) {
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  const lighter = Math.max(foregroundLuminance, backgroundLuminance)
  const darker = Math.min(foregroundLuminance, backgroundLuminance)
  return (lighter + 0.05) / (darker + 0.05)
}

test.each(['color-surface', 'color-white'])(
  'keeps muted body text at 4.5:1 or better on %s',
  (backgroundToken) => {
    expect(
      contrastRatio(
        readColorToken('color-ink-muted'),
        readColorToken(backgroundToken),
      ),
    ).toBeGreaterThanOrEqual(4.5)
  },
)

test('disables all shared loading animations when reduced motion is requested', () => {
  expect(globalCss).toMatch(
    /@media\s*\(prefers-reduced-motion:\s*reduce\)\s*\{\s*\.button__spinner,\s*\.async-state__loader,\s*\.is-spinning\s*\{\s*animation:\s*none;\s*\}\s*\}/,
  )
})

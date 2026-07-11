import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'

import { App } from './App'

test('renders the product shell and dashboard route', async () => {
  render(<App />)

  expect(await screen.findByRole('link', { name: 'MatchLab' })).toBeVisible()
  expect(screen.getByRole('heading', { name: '分析总览' })).toBeVisible()
})

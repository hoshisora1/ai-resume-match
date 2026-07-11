import { expect, test } from 'vitest'

import { createQueryClient } from './queryClient'

test('creates independent production clients with the approved retry defaults', () => {
  const first = createQueryClient()
  const second = createQueryClient()

  try {
    expect(first).not.toBe(second)
    expect(first.getDefaultOptions()).toMatchObject({
      queries: { retry: 1 },
      mutations: { retry: 0 },
    })
  } finally {
    first.clear()
    second.clear()
  }
})

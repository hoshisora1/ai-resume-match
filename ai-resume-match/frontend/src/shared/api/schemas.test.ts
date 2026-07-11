import { describe, expect, test } from 'vitest'

import { analysisPageSchema, analysisSummarySchema } from './schemas'

const listItem = {
  taskId: 30,
  jobTitle: 'Backend Engineer',
  resumeFileName: 'resume.pdf',
  status: 'SUCCESS',
  matchScore: 88,
  attemptCount: 1,
  maxAttempts: 3,
  failureCode: null,
  createdAt: '2026-07-10T09:00:00',
  updatedAt: '2026-07-10T09:05:00',
  completedAt: '2026-07-10T09:05:00',
} as const

describe('analysisPageSchema', () => {
  test.each([0, 101])('rejects backend-invalid page size %s', (size) => {
    expect(
      analysisPageSchema.safeParse({
        items: [],
        page: 0,
        size,
        totalElements: 0,
        totalPages: 0,
      }).success,
    ).toBe(false)
  })

  test('rejects more items than the requested page size', () => {
    expect(
      analysisPageSchema.safeParse({
        items: [listItem, { ...listItem, taskId: 31 }],
        page: 0,
        size: 1,
        totalElements: 2,
        totalPages: 2,
      }).success,
    ).toBe(false)
  })

  test('rejects more items than total elements', () => {
    expect(
      analysisPageSchema.safeParse({
        items: [listItem, { ...listItem, taskId: 31 }],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      }).success,
    ).toBe(false)
  })

  test('rejects totalPages that does not match totalElements and size', () => {
    expect(
      analysisPageSchema.safeParse({
        items: [listItem],
        page: 0,
        size: 10,
        totalElements: 21,
        totalPages: 2,
      }).success,
    ).toBe(false)
  })

  test('accepts an empty dataset with an out-of-range requested page', () => {
    expect(
      analysisPageSchema.safeParse({
        items: [],
        page: 8,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      }).success,
    ).toBe(true)
  })

  test('accepts an empty out-of-range page for a non-empty dataset', () => {
    expect(
      analysisPageSchema.safeParse({
        items: [],
        page: 8,
        size: 10,
        totalElements: 21,
        totalPages: 3,
      }).success,
    ).toBe(true)
  })
})

describe('analysisSummarySchema', () => {
  test('accepts disjoint represented counts with unclassified terminal tasks', () => {
    expect(
      analysisSummarySchema.safeParse({
        totalCount: 10,
        successCount: 3,
        inProgressCount: 2,
        retryableFailureCount: 1,
        averageMatchScore: 82.4,
      }).success,
    ).toBe(true)
  })

  test.each([
    ['successCount', { successCount: 4, inProgressCount: 0, retryableFailureCount: 0 }],
    ['inProgressCount', { successCount: 0, inProgressCount: 4, retryableFailureCount: 0 }],
    ['retryableFailureCount', { successCount: 0, inProgressCount: 0, retryableFailureCount: 4 }],
  ] as const)('rejects %s greater than totalCount', (_field, counts) => {
    expect(
      analysisSummarySchema.safeParse({
        totalCount: 3,
        ...counts,
        averageMatchScore: null,
      }).success,
    ).toBe(false)
  })

  test('rejects represented status counts whose sum exceeds totalCount', () => {
    expect(
      analysisSummarySchema.safeParse({
        totalCount: 5,
        successCount: 3,
        inProgressCount: 2,
        retryableFailureCount: 1,
        averageMatchScore: 82.4,
      }).success,
    ).toBe(false)
  })
})

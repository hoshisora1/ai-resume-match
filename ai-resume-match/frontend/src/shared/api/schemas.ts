import { z } from 'zod'

const idSchema = z.number().int().positive()
const countSchema = z.number().int().nonnegative()
const pageSizeSchema = z.number().int().min(1).max(100)
const matchScoreSchema = z.number().int().min(0).max(100)
const timestampSchema = z.iso.datetime({ local: true })

export const analysisStatusSchema = z.enum([
  'PENDING',
  'RUNNING',
  'SUCCESS',
  'FAILED_RETRYABLE',
  'FAILED_FINAL',
  'CANCELLED',
  'FAILED',
])

export const analysisTaskSchema = z.object({
  taskId: idSchema,
  resumeId: idSchema,
  jobDescriptionId: idSchema,
  jobTitle: z.string().nullable(),
  resumeFileName: z.string().nullable(),
  matchScore: matchScoreSchema.nullable(),
  status: analysisStatusSchema,
  attemptCount: countSchema,
  maxAttempts: countSchema,
  failureCode: z.string().nullable(),
  failureMessage: z.string().nullable(),
  nextRetryAt: timestampSchema.nullable(),
  startedAt: timestampSchema.nullable(),
  completedAt: timestampSchema.nullable(),
  createdAt: timestampSchema,
  updatedAt: timestampSchema,
})

export const analysisListItemSchema = z.object({
  taskId: idSchema,
  jobTitle: z.string().nullable(),
  resumeFileName: z.string().nullable(),
  status: analysisStatusSchema,
  matchScore: matchScoreSchema.nullable(),
  attemptCount: countSchema,
  maxAttempts: countSchema,
  failureCode: z.string().nullable(),
  createdAt: timestampSchema,
  updatedAt: timestampSchema,
  completedAt: timestampSchema.nullable(),
})

export const analysisPageSchema = z
  .object({
    items: z.array(analysisListItemSchema),
    page: countSchema,
    size: pageSizeSchema,
    totalElements: countSchema,
    totalPages: countSchema,
  })
  .superRefine((page, context) => {
    if (page.items.length > page.size) {
      context.addIssue({
        code: 'custom',
        message: 'Page items exceed page size',
        path: ['items'],
      })
    }

    if (page.items.length > page.totalElements) {
      context.addIssue({
        code: 'custom',
        message: 'Page items exceed total elements',
        path: ['items'],
      })
    }

    const expectedTotalPages = Math.ceil(page.totalElements / page.size)
    if (page.totalPages !== expectedTotalPages) {
      context.addIssue({
        code: 'custom',
        message: 'Total pages do not match total elements and page size',
        path: ['totalPages'],
      })
    }
  })

export const analysisSummarySchema = z
  .object({
    totalCount: countSchema,
    successCount: countSchema,
    inProgressCount: countSchema,
    retryableFailureCount: countSchema,
    averageMatchScore: z.number().min(0).max(100).nullable(),
  })
  .superRefine((summary, context) => {
    const representedCounts = [
      ['successCount', summary.successCount],
      ['inProgressCount', summary.inProgressCount],
      ['retryableFailureCount', summary.retryableFailureCount],
    ] as const

    for (const [field, count] of representedCounts) {
      if (count > summary.totalCount) {
        context.addIssue({
          code: 'custom',
          message: `${field} exceeds totalCount`,
          path: [field],
        })
      }
    }

    const representedTotal = representedCounts.reduce(
      (total, [, count]) => total + count,
      0,
    )
    if (representedTotal > summary.totalCount) {
      context.addIssue({
        code: 'custom',
        message: 'Represented status counts exceed totalCount',
        path: ['totalCount'],
      })
    }
  })

export const matchReportSchema = z.object({
  taskId: idSchema,
  matchScore: matchScoreSchema,
  reportContent: z.string(),
  createdAt: timestampSchema,
})

export const apiErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  requestId: z.string().nullable(),
})

export const healthSchema = z.object({
  status: z.string().min(1),
})

export type AnalysisStatus = z.infer<typeof analysisStatusSchema>
export type AnalysisTask = z.infer<typeof analysisTaskSchema>
export type AnalysisListItem = z.infer<typeof analysisListItemSchema>
export type AnalysisPage = z.infer<typeof analysisPageSchema>
export type AnalysisSummary = z.infer<typeof analysisSummarySchema>
export type MatchReport = z.infer<typeof matchReportSchema>
export type ApiErrorResponse = z.infer<typeof apiErrorSchema>
export type BackendHealth = z.infer<typeof healthSchema>

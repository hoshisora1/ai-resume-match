import { z } from 'zod'

import type {
  AnalysisProvenance as GeneratedAnalysisProvenance,
  ApiProblemDetail,
  MatchReportResponse as GeneratedMatchReportResponse,
  StructuredMatchReport as GeneratedStructuredMatchReport,
} from './generated'

const idSchema = z.number().int().positive()
const countSchema = z.number().int().nonnegative()
const pageSizeSchema = z.number().int().min(1).max(100)
const matchScoreSchema = z.number().int().min(0).max(100)
const timestampSchema = z.iso.datetime({ local: true })
const evidenceIdSchema = z.string().regex(/^resume:\d+$/)
const requirementIdSchema = z.string().regex(/^requirement:\d+$/)

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

export const requirementStatusSchema = z.enum([
  'supported',
  'partial',
  'not_found',
])

const evidenceVerificationSchema = z.object({
  verifierVersion: z.string().min(1).max(120),
  status: requirementStatusSchema,
  termCoverage: z.number().min(0).max(1),
  reason: z.string().min(1).max(120),
  evidenceIds: z.array(evidenceIdSchema).max(5),
})

export const requirementResultSchema = z.object({
  requirementId: requirementIdSchema,
  text: z.string().min(1).max(500),
  mustHave: z.boolean(),
  weight: z.number().int().min(1).max(5),
  modelStatus: requirementStatusSchema,
  status: requirementStatusSchema,
  explanation: z.string().min(1).max(500),
  evidenceIds: z.array(evidenceIdSchema).max(5),
  verification: evidenceVerificationSchema,
})

const groundedClaimSchema = z.object({
  claim: z.string().min(1).max(500),
  evidenceIds: z.array(evidenceIdSchema).min(1).max(5),
})

export const structuredEvidenceSchema = z.object({
  evidenceId: evidenceIdSchema,
  excerpt: z.string().min(1).max(900),
  score: z.number().min(-1).max(1),
  sourceStart: z.number().int().nonnegative().nullable(),
  sourceEnd: z.number().int().positive().nullable(),
})

const scoreBreakdownSchema = z
  .object({
    rawScore: matchScoreSchema,
    finalScore: matchScoreSchema,
    totalWeight: z.number().int().positive(),
    supportedWeight: countSchema,
    partialWeight: countSchema,
    missingWeight: countSchema,
    mustHaveCapApplied: z.boolean(),
  })
  .superRefine((breakdown, context) => {
    if (
      breakdown.supportedWeight +
        breakdown.partialWeight +
        breakdown.missingWeight !==
      breakdown.totalWeight
    ) {
      context.addIssue({
        code: 'custom',
        message: 'Score weights do not sum to totalWeight',
        path: ['totalWeight'],
      })
    }
    if (breakdown.finalScore > breakdown.rawScore) {
      context.addIssue({
        code: 'custom',
        message: 'Final score exceeds raw score',
        path: ['finalScore'],
      })
    }
    if (breakdown.mustHaveCapApplied !== (breakdown.finalScore < breakdown.rawScore)) {
      context.addIssue({
        code: 'custom',
        message: 'Must-have cap flag does not match score reduction',
        path: ['mustHaveCapApplied'],
      })
    }
  })

export const structuredMatchReportSchema = z
  .object({
    schemaVersion: z.literal('match-report-v2'),
    matchScore: matchScoreSchema,
    requirements: z.array(requirementResultSchema).min(1).max(5),
    coreClaims: z.array(groundedClaimSchema).max(12),
    matchedSkills: z.array(groundedClaimSchema).max(20),
    skillGaps: z.array(z.string().min(1).max(500)).min(1).max(20),
    recommendations: z.array(z.string().min(1).max(500)).min(3).max(5),
    interviewQuestions: z.array(z.string().min(1).max(500)).min(3).max(8),
    evidence: z.array(structuredEvidenceSchema).max(12),
    scoreBreakdown: scoreBreakdownSchema,
  })
  .superRefine((report, context) => {
    if (report.matchScore !== report.scoreBreakdown.finalScore) {
      context.addIssue({
        code: 'custom',
        message: 'Structured report score does not match score breakdown',
        path: ['matchScore'],
      })
    }
    const evidenceIds = report.evidence.map((item) => item.evidenceId)
    const available = new Set(evidenceIds)
    if (available.size !== evidenceIds.length) {
      context.addIssue({
        code: 'custom',
        message: 'Structured evidence IDs are not unique',
        path: ['evidence'],
      })
    }
    const referenced = new Set([
      ...report.requirements.flatMap((item) => item.evidenceIds),
      ...report.coreClaims.flatMap((item) => item.evidenceIds),
      ...report.matchedSkills.flatMap((item) => item.evidenceIds),
    ])
    if (
      available.size !== referenced.size ||
      [...referenced].some((evidenceId) => !available.has(evidenceId))
    ) {
      context.addIssue({
        code: 'custom',
        message: 'Structured evidence graph is inconsistent',
        path: ['evidence'],
      })
    }
    const statusRank = { not_found: 0, partial: 1, supported: 2 } as const
    let supportedWeight = 0
    let partialWeight = 0
    let missingMustHave = false
    for (const [index, requirement] of report.requirements.entries()) {
      const requirementEvidence = new Set(requirement.evidenceIds)
      const verificationEvidence = new Set(requirement.verification.evidenceIds)
      if (
        requirementEvidence.size !== requirement.evidenceIds.length ||
        verificationEvidence.size !== requirement.verification.evidenceIds.length
      ) {
        context.addIssue({
          code: 'custom',
          message: 'Requirement evidence IDs are not unique',
          path: ['requirements', index, 'evidenceIds'],
        })
      }
      if (
        [...requirementEvidence].some(
          (evidenceId) => !verificationEvidence.has(evidenceId),
        )
      ) {
        context.addIssue({
          code: 'custom',
          message: 'Requirement uses evidence rejected by the verifier',
          path: ['requirements', index, 'evidenceIds'],
        })
      }
      if (
        statusRank[requirement.status] > statusRank[requirement.modelStatus] ||
        statusRank[requirement.status] > statusRank[requirement.verification.status]
      ) {
        context.addIssue({
          code: 'custom',
          message: 'Final status upgrades an unverified proposal',
          path: ['requirements', index, 'status'],
        })
      }
      if ((requirement.status === 'not_found') !== (requirement.evidenceIds.length === 0)) {
        context.addIssue({
          code: 'custom',
          message: 'Requirement status and evidence disagree',
          path: ['requirements', index, 'evidenceIds'],
        })
      }
      if (
        (requirement.verification.status === 'not_found') !==
        (requirement.verification.evidenceIds.length === 0)
      ) {
        context.addIssue({
          code: 'custom',
          message: 'Verification status and evidence disagree',
          path: ['requirements', index, 'verification', 'evidenceIds'],
        })
      }
      if (requirement.status === 'supported') supportedWeight += requirement.weight
      if (requirement.status === 'partial') partialWeight += requirement.weight
      if (requirement.status === 'not_found' && requirement.mustHave) {
        missingMustHave = true
      }
    }
    const totalWeight = report.requirements.reduce(
      (total, requirement) => total + requirement.weight,
      0,
    )
    const missingWeight = totalWeight - supportedWeight - partialWeight
    const rawScore = Math.floor(
      ((supportedWeight + partialWeight * 0.5) * 100) / totalWeight + 0.5,
    )
    const finalScore = missingMustHave ? Math.min(rawScore, 69) : rawScore
    const breakdown = report.scoreBreakdown
    if (
      report.matchScore !== finalScore ||
      breakdown.rawScore !== rawScore ||
      breakdown.finalScore !== finalScore ||
      breakdown.totalWeight !== totalWeight ||
      breakdown.supportedWeight !== supportedWeight ||
      breakdown.partialWeight !== partialWeight ||
      breakdown.missingWeight !== missingWeight ||
      breakdown.mustHaveCapApplied !== (finalScore < rawScore)
    ) {
      context.addIssue({
        code: 'custom',
        message: 'Structured score does not match deterministic requirements',
        path: ['scoreBreakdown'],
      })
    }
  }) satisfies z.ZodType<NonNullable<GeneratedStructuredMatchReport>>

const modelUsageSchema = z
  .object({
    promptTokens: countSchema,
    completionTokens: countSchema,
    totalTokens: countSchema,
    providerReported: z.boolean(),
    estimatedCostUsd: z
      .string()
      .regex(/^(0|[1-9]\d*)(\.\d{1,8})?$/)
      .nullable()
      .optional()
      .default(null),
    pricingVersion: z.string().min(1).max(120).nullable().optional().default(null),
  })
  .superRefine((usage, context) => {
    if ((usage.estimatedCostUsd === null) !== (usage.pricingVersion === null)) {
      context.addIssue({
        code: 'custom',
        message: 'Cost estimate and pricing version must be present together',
        path: ['estimatedCostUsd'],
      })
    }
    if (usage.estimatedCostUsd !== null && !usage.providerReported) {
      context.addIssue({
        code: 'custom',
        message: 'Cost estimate requires complete provider usage',
        path: ['providerReported'],
      })
    }
  })

const runMetadataSchema = z.object({
  schemaVersion: z.literal('agent-run-v1'),
  requestSchemaVersion: z.literal('agent-analysis-request-v1'),
  agentRuntimeVersion: z.literal('bounded-tool-agent-v1'),
  inputFingerprintVersion: z.literal('sha256-task-scoped-length-prefixed-v1'),
  inputFingerprint: z.string().regex(/^[0-9a-f]{64}$/),
  chatProviderCalls: z.number().int().min(1).max(12),
  chatProviderDurationMs: z.number().int().min(0).max(3_600_000),
  toolDurationMs: z.number().int().min(0).max(3_600_000),
  totalDurationMs: z.number().int().min(0).max(3_600_000),
  contextCharsSent: z.number().int().min(0).max(100_000_000),
})

export const analysisProvenanceSchema = z
  .object({
    schemaVersion: z.literal('analysis-run-v1'),
    correlationId: z.string().min(1).max(128),
    traceId: z
      .string()
      .regex(/^[0-9a-f]{32}$/)
      .nullable()
      .optional()
      .default(null),
    model: z.string().min(1),
    promptVersion: z.string().min(1).max(120),
    retrieverVersion: z.string().min(1).max(120),
    verifierVersion: z.string().min(1).max(120),
    steps: z.number().int().positive(),
    runMetadata: runMetadataSchema.nullable().optional().default(null),
    modelUsage: modelUsageSchema,
    toolTrace: z.array(
      z.object({
        name: z.string().min(1),
        outcome: z.string().min(1),
        durationMs: countSchema.max(3_600_000),
      }),
    ),
  })
  .superRefine((provenance, context) => {
    const run = provenance.runMetadata
    if (run === null) return
    if (run.chatProviderCalls !== provenance.steps) {
      context.addIssue({
        code: 'custom',
        message: 'Chat provider calls must match agent steps',
        path: ['runMetadata', 'chatProviderCalls'],
      })
    }
    if (run.chatProviderDurationMs > run.totalDurationMs) {
      context.addIssue({
        code: 'custom',
        message: 'Chat provider duration must not exceed total duration',
        path: ['runMetadata', 'chatProviderDurationMs'],
      })
    }
    const tracedToolDuration = provenance.toolTrace.reduce(
      (total, trace) => total + trace.durationMs,
      0,
    )
    if (run.toolDurationMs !== tracedToolDuration) {
      context.addIssue({
        code: 'custom',
        message: 'Tool duration must match the tool trace',
        path: ['runMetadata', 'toolDurationMs'],
      })
    }
  }) satisfies z.ZodType<NonNullable<GeneratedAnalysisProvenance>>

export const matchReportSchema = z
  .object({
    taskId: idSchema,
    matchScore: matchScoreSchema,
    reportContent: z.string(),
    reportSchemaVersion: z.enum(['markdown-v1', 'match-report-v2']),
    structuredReport: structuredMatchReportSchema.nullable(),
    provenance: analysisProvenanceSchema.nullable(),
    createdAt: timestampSchema,
  })
  .superRefine((report, context) => {
    const structured = report.reportSchemaVersion === 'match-report-v2'
    if (structured !== (report.structuredReport !== null && report.provenance !== null)) {
      context.addIssue({
        code: 'custom',
        message: 'Report schema version does not match structured payloads',
        path: ['reportSchemaVersion'],
      })
    }
    if (report.structuredReport !== null && report.structuredReport.matchScore !== report.matchScore) {
      context.addIssue({
        code: 'custom',
        message: 'Top-level score does not match structured report',
        path: ['matchScore'],
      })
    }
  }) satisfies z.ZodType<GeneratedMatchReportResponse>

const problemDetailsSchema: z.ZodType<ApiProblemDetail> = z.object({
  type: z.string().min(1),
  title: z.string().min(1),
  status: z.number().int().min(400).max(599),
  detail: z.string(),
  instance: z.string().min(1),
  code: z.string(),
  message: z.string(),
  requestId: z.string().nullable(),
})

const legacyApiErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  requestId: z.string().nullable().optional(),
})

// Keep parsing the legacy shape during rolling deployments while making the
// generated RFC 9457 contract the primary response shape.
export const apiErrorSchema = z.union([
  problemDetailsSchema,
  legacyApiErrorSchema,
])

export const healthSchema = z.object({
  status: z.string().min(1),
})

export type AnalysisStatus = z.infer<typeof analysisStatusSchema>
export type AnalysisTask = z.infer<typeof analysisTaskSchema>
export type AnalysisListItem = z.infer<typeof analysisListItemSchema>
export type AnalysisPage = z.infer<typeof analysisPageSchema>
export type AnalysisSummary = z.infer<typeof analysisSummarySchema>
export type MatchReport = z.infer<typeof matchReportSchema>
export type StructuredMatchReport = z.infer<typeof structuredMatchReportSchema>
export type RequirementResult = z.infer<typeof requirementResultSchema>
export type StructuredEvidence = z.infer<typeof structuredEvidenceSchema>
export type AnalysisProvenance = z.infer<typeof analysisProvenanceSchema>
export type ApiErrorResponse = z.infer<typeof apiErrorSchema>
export type BackendHealth = z.infer<typeof healthSchema>

type IsExact<Actual, Expected> = [Actual] extends [Expected]
  ? [Expected] extends [Actual]
    ? true
    : false
  : false
type Assert<T extends true> = T

// Compilation is the final drift gate between runtime validation and the
// OpenAPI-generated wire types. A field becoming optional, nullable, renamed,
// or version-widened on either side must fail `npm run typecheck`.
export type MatchReportOpenApiContractIsExact = Assert<
  IsExact<MatchReport, GeneratedMatchReportResponse>
>
export type StructuredReportOpenApiContractIsExact = Assert<
  IsExact<StructuredMatchReport, NonNullable<GeneratedStructuredMatchReport>>
>
export type ProvenanceOpenApiContractIsExact = Assert<
  IsExact<AnalysisProvenance, NonNullable<GeneratedAnalysisProvenance>>
>

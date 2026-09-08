import { describe, expect, test } from 'vitest'

import {
  analysisPageSchema,
  analysisSummarySchema,
  matchReportSchema,
} from './schemas'

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

function structuredReportPayload() {
  return {
    taskId: 30,
    matchScore: 100,
    reportContent: '# report',
    reportSchemaVersion: 'match-report-v2',
    structuredReport: {
      schemaVersion: 'match-report-v2',
      matchScore: 100,
      requirements: [
        {
          requirementId: 'requirement:0',
          text: 'Java',
          mustHave: true,
          weight: 2,
          modelStatus: 'supported',
          status: 'supported',
          explanation: 'supported',
          evidenceIds: ['resume:0'],
          verification: {
            verifierVersion: 'verifier-v1',
            status: 'supported',
            termCoverage: 1,
            reason: 'required_terms_supported',
            evidenceIds: ['resume:0'],
          },
        },
      ],
      coreClaims: [{ claim: 'Java supported', evidenceIds: ['resume:0'] }],
      matchedSkills: [],
      skillGaps: ['none'],
      recommendations: ['one', 'two', 'three'],
      interviewQuestions: ['one?', 'two?', 'three?'],
      evidence: [
        {
          evidenceId: 'resume:0',
          excerpt: 'Built Java services.',
          score: 0.9,
          sourceStart: 0,
          sourceEnd: 20,
        },
      ],
      scoreBreakdown: {
        rawScore: 100,
        finalScore: 100,
        totalWeight: 2,
        supportedWeight: 2,
        partialWeight: 0,
        missingWeight: 0,
        mustHaveCapApplied: false,
      },
    },
    provenance: {
      schemaVersion: 'analysis-run-v1',
      correlationId: 'correlation-30',
      traceId: '0123456789abcdef0123456789abcdef',
      model: 'test-model',
      promptVersion: 'prompt-v3',
      retrieverVersion: 'retriever-v1',
      verifierVersion: 'verifier-v1',
      steps: 3,
      runMetadata: {
        schemaVersion: 'agent-run-v1',
        requestSchemaVersion: 'agent-analysis-request-v1',
        agentRuntimeVersion: 'bounded-tool-agent-v1',
        inputFingerprintVersion: 'sha256-task-scoped-length-prefixed-v1',
        inputFingerprint:
          '6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1',
        chatProviderCalls: 3,
        chatProviderDurationMs: 80,
        toolDurationMs: 2,
        totalDurationMs: 125,
        contextCharsSent: 2048,
      },
      modelUsage: {
        promptTokens: 100,
        completionTokens: 30,
        totalTokens: 130,
        providerReported: true,
        estimatedCostUsd: '0.00027000',
        pricingVersion: 'test-price-2026-08-01',
      },
      toolTrace: [
        { name: 'submit_match_report', outcome: 'success', durationMs: 2 },
      ],
    },
    createdAt: '2026-07-10T09:05:00',
  }
}

describe('matchReportSchema', () => {
  test('accepts a versioned structured report and provenance graph', () => {
    expect(matchReportSchema.safeParse(structuredReportPayload()).success).toBe(true)
  })

  test('rejects unknown cited evidence and a mismatched top-level score', () => {
    const unknownEvidence = structuredReportPayload()
    unknownEvidence.structuredReport.requirements[0]!.evidenceIds = ['resume:999']
    expect(matchReportSchema.safeParse(unknownEvidence).success).toBe(false)

    const wrongScore = structuredReportPayload()
    wrongScore.matchScore = 77
    expect(matchReportSchema.safeParse(wrongScore).success).toBe(false)
  })

  test('rejects requirement evidence that was not accepted by the verifier', () => {
    const rejectedEvidence = structuredReportPayload()
    rejectedEvidence.structuredReport.requirements[0]!.verification.evidenceIds = []
    expect(matchReportSchema.safeParse(rejectedEvidence).success).toBe(false)
  })

  test('rejects an unversioned cost estimate or cost based on partial usage', () => {
    const valid = structuredReportPayload()
    const missingPricingVersion = {
      ...valid,
      provenance: {
        ...valid.provenance,
        modelUsage: {
          ...valid.provenance.modelUsage,
          pricingVersion: null,
        },
      },
    }
    expect(matchReportSchema.safeParse(missingPricingVersion).success).toBe(false)

    const partialUsage = structuredReportPayload()
    partialUsage.provenance.modelUsage.providerReported = false
    expect(matchReportSchema.safeParse(partialUsage).success).toBe(false)
  })

  test('accepts stored v1 provenance created before cost fields existed', () => {
    const legacyCostlessProvenance = structuredReportPayload()
    Reflect.deleteProperty(
      legacyCostlessProvenance.provenance.modelUsage,
      'estimatedCostUsd',
    )
    Reflect.deleteProperty(legacyCostlessProvenance.provenance.modelUsage, 'pricingVersion')

    const parsed = matchReportSchema.parse(legacyCostlessProvenance)

    expect(parsed.provenance?.modelUsage.estimatedCostUsd).toBeNull()
    expect(parsed.provenance?.modelUsage.pricingVersion).toBeNull()
  })

  test('accepts stored v1 provenance created before trace IDs existed', () => {
    const legacyTraceLessProvenance = structuredReportPayload()
    Reflect.deleteProperty(legacyTraceLessProvenance.provenance, 'traceId')

    const parsed = matchReportSchema.parse(legacyTraceLessProvenance)

    expect(parsed.provenance?.traceId).toBeNull()
  })

  test('accepts stored v1 provenance created before run metadata existed', () => {
    const legacyRunMetadataLessProvenance = structuredReportPayload()
    Reflect.deleteProperty(legacyRunMetadataLessProvenance.provenance, 'runMetadata')

    const parsed = matchReportSchema.parse(legacyRunMetadataLessProvenance)

    expect(parsed.provenance?.runMetadata).toBeNull()
  })

  test('rejects inconsistent or malformed run metadata', () => {
    const wrongCallCount = structuredReportPayload()
    wrongCallCount.provenance.runMetadata.chatProviderCalls = 2
    expect(matchReportSchema.safeParse(wrongCallCount).success).toBe(false)

    const wrongToolDuration = structuredReportPayload()
    wrongToolDuration.provenance.runMetadata.toolDurationMs = 3
    expect(matchReportSchema.safeParse(wrongToolDuration).success).toBe(false)

    const malformedFingerprint = structuredReportPayload()
    malformedFingerprint.provenance.runMetadata.inputFingerprint = 'not-a-fingerprint'
    expect(matchReportSchema.safeParse(malformedFingerprint).success).toBe(false)
  })

  test('rejects malformed trace IDs', () => {
    const malformed = structuredReportPayload()
    malformed.provenance.traceId = 'not-a-trace-id'

    expect(matchReportSchema.safeParse(malformed).success).toBe(false)
  })

  test('accepts a legacy Markdown report with null structured fields', () => {
    expect(
      matchReportSchema.safeParse({
        taskId: 30,
        matchScore: 88,
        reportContent: '# legacy',
        reportSchemaVersion: 'markdown-v1',
        structuredReport: null,
        provenance: null,
        createdAt: '2026-07-10T09:05:00',
      }).success,
    ).toBe(true)
  })
})

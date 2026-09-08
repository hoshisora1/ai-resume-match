import { expect, test, vi } from 'vitest'

import type { MatchReport } from '../../shared/api/schemas'
import { createReportExport, saveReportExport } from './reportExport'

const report = {
  taskId: 42,
  matchScore: 88,
  reportSchemaVersion: 'markdown-v1',
  structuredReport: null,
  provenance: null,
  reportContent: '# 合成报告\n\n安全内容\n',
  createdAt: '2026-08-13T09:00:00Z',
} satisfies MatchReport

test('creates a stable allowlisted JSON export without source documents', () => {
  const file = createReportExport(
    {
      ...report,
      resumeText: 'must not escape',
      jobDescription: 'must not escape',
    } as MatchReport,
    'json',
  )
  const payload = JSON.parse(file.content) as Record<string, unknown>

  expect(file.fileName).toBe('analysis-42-match-report.json')
  expect(file.mimeType).toBe('application/json;charset=utf-8')
  expect(payload).toMatchObject({
    schemaVersion: 'match-report-export-v1',
    sourceDocumentsIncluded: false,
    taskId: 42,
    matchScore: 88,
  })
  expect(payload).not.toHaveProperty('resumeText')
  expect(payload).not.toHaveProperty('jobDescription')
  expect(file.content.endsWith('\n')).toBe(true)
})

test('normalizes the Markdown export to one trailing newline', () => {
  const file = createReportExport(report, 'markdown')

  expect(file).toEqual({
    content: '# 合成报告\n\n安全内容\n',
    fileName: 'analysis-42-match-report.md',
    mimeType: 'text/markdown;charset=utf-8',
  })
})

test('downloads through a temporary object URL and always revokes it', () => {
  const click = vi.fn()
  const anchor = { click, download: '', href: '' }
  const createObjectURL = vi.fn(() => 'blob:report-export')
  const revokeObjectURL = vi.fn()
  const documentRef = {
    createElement: vi.fn(() => anchor),
  } as unknown as Document

  saveReportExport(
    createReportExport(report, 'json'),
    documentRef,
    { createObjectURL, revokeObjectURL },
  )

  expect(anchor.download).toBe('analysis-42-match-report.json')
  expect(anchor.href).toBe('blob:report-export')
  expect(click).toHaveBeenCalledOnce()
  expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:report-export')
})

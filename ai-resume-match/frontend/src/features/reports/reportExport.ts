import type { MatchReport } from '../../shared/api/schemas'

export type ReportExportFormat = 'json' | 'markdown'

export interface ReportExportFile {
  content: string
  fileName: string
  mimeType: string
}

export function createReportExport(
  report: MatchReport,
  format: ReportExportFormat,
): ReportExportFile {
  const baseName = `analysis-${report.taskId}-match-report`
  if (format === 'markdown') {
    return {
      content: `${report.reportContent.trimEnd()}\n`,
      fileName: `${baseName}.md`,
      mimeType: 'text/markdown;charset=utf-8',
    }
  }

  const payload = {
    schemaVersion: 'match-report-export-v1',
    sourceDocumentsIncluded: false,
    taskId: report.taskId,
    matchScore: report.matchScore,
    reportSchemaVersion: report.reportSchemaVersion,
    structuredReport: report.structuredReport,
    provenance: report.provenance,
    reportContent: report.reportContent,
    createdAt: report.createdAt,
  }
  return {
    content: `${JSON.stringify(payload, null, 2)}\n`,
    fileName: `${baseName}.json`,
    mimeType: 'application/json;charset=utf-8',
  }
}

export function saveReportExport(
  file: ReportExportFile,
  documentRef: Document = document,
  urlApi: Pick<typeof URL, 'createObjectURL' | 'revokeObjectURL'> = URL,
) {
  const objectUrl = urlApi.createObjectURL(
    new Blob([file.content], { type: file.mimeType }),
  )
  try {
    const anchor = documentRef.createElement('a')
    anchor.download = file.fileName
    anchor.href = objectUrl
    anchor.click()
  } finally {
    urlApi.revokeObjectURL(objectUrl)
  }
}

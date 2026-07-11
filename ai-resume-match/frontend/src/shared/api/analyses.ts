import { apiRequest } from './client'
import {
  analysisPageSchema,
  analysisSummarySchema,
  analysisTaskSchema,
  healthSchema,
  matchReportSchema,
  type AnalysisStatus,
} from './schemas'

export interface ListAnalysesParams {
  status?: AnalysisStatus | undefined
  page: number
  size: number
}

export interface CreateAnalysisSubmissionInput {
  file: File
  jobTitle: string
  jobContent: string
}

export function getAnalysisSummary(signal?: AbortSignal) {
  return apiRequest(
    '/api/analysis/summary',
    analysisSummarySchema,
    signal ? { signal } : undefined,
  )
}

export function listAnalyses(
  { status, page, size }: ListAnalysesParams,
  signal?: AbortSignal,
) {
  const searchParams = new URLSearchParams()
  if (status !== undefined) {
    searchParams.set('status', status)
  }
  searchParams.set('page', String(page))
  searchParams.set('size', String(size))

  return apiRequest(
    `/api/analysis?${searchParams.toString()}`,
    analysisPageSchema,
    signal ? { signal } : undefined,
  )
}

export function createAnalysisSubmission({
  file,
  jobTitle,
  jobContent,
}: CreateAnalysisSubmissionInput) {
  const formData = new FormData()
  formData.set('file', file)
  formData.set('jobTitle', jobTitle)
  formData.set('jobContent', jobContent)

  return apiRequest('/api/analysis-submissions', analysisTaskSchema, {
    method: 'POST',
    body: formData,
  })
}

export function getAnalysisTask(taskId: number) {
  return apiRequest(`/api/analysis/${taskId}`, analysisTaskSchema)
}

export function retryAnalysisTask(taskId: number) {
  return apiRequest(`/api/analysis/${taskId}/retry`, analysisTaskSchema, {
    method: 'POST',
  })
}

export function getMatchReport(taskId: number) {
  return apiRequest(`/api/analysis/${taskId}/report`, matchReportSchema)
}

export function getBackendHealth(signal?: AbortSignal) {
  return apiRequest(
    '/backend-health',
    healthSchema,
    signal ? { signal } : undefined,
  )
}

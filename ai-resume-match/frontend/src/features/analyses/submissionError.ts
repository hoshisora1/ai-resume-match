import { ApiError } from '../../shared/api/client'

export const SUBMISSION_ERROR_MESSAGE = '提交失败，请稍后重试。'

export interface SubmissionError {
  readonly code: string
  readonly message: string
  readonly requestId?: string | undefined
}

export function summarizeSubmissionError(error: unknown): SubmissionError {
  if (error instanceof ApiError) {
    return {
      code: error.code,
      message: SUBMISSION_ERROR_MESSAGE,
      ...(error.requestId === undefined
        ? {}
        : { requestId: error.requestId }),
    }
  }

  return {
    code: 'SUBMISSION_FAILED',
    message: SUBMISSION_ERROR_MESSAGE,
  }
}

import type { ZodType } from 'zod'

import { apiErrorSchema } from './schemas'

const INVALID_RESPONSE_MESSAGE = '服务返回了无法识别的数据'
const HTTP_ERROR_MESSAGE = '请求失败'
const NETWORK_ERROR_MESSAGE = '无法连接到服务'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

function isAbortError(error: unknown) {
  return (
    typeof error === 'object' &&
    error !== null &&
    'name' in error &&
    error.name === 'AbortError'
  )
}

async function readJson(response: Response): Promise<unknown> {
  try {
    const payload: unknown = await response.json()
    return payload
  } catch (error) {
    if (isAbortError(error)) {
      throw error
    }
    return null
  }
}

export async function apiRequest<T>(
  path: string,
  schema: ZodType<T>,
  init?: RequestInit,
): Promise<T> {
  try {
    const headers = new Headers(init?.headers)
    headers.set('Accept', 'application/json')
    headers.set('X-Request-Id', crypto.randomUUID())

    const response = await fetch(path, {
      ...init,
      headers,
    })
    const payload = await readJson(response)

    if (!response.ok) {
      const parsedError = apiErrorSchema.safeParse(payload)
      if (parsedError.success) {
        throw new ApiError(
          response.status,
          parsedError.data.code,
          parsedError.data.message,
          parsedError.data.requestId ?? undefined,
        )
      }

      throw new ApiError(
        response.status,
        'HTTP_ERROR',
        HTTP_ERROR_MESSAGE,
        response.headers.get('X-Request-Id') ?? undefined,
      )
    }

    const parsedResponse = schema.safeParse(payload)
    if (!parsedResponse.success) {
      throw new ApiError(
        response.status,
        'INVALID_RESPONSE',
        INVALID_RESPONSE_MESSAGE,
      )
    }

    return parsedResponse.data
  } catch (error) {
    if (error instanceof ApiError || isAbortError(error)) {
      throw error
    }

    throw new ApiError(0, 'NETWORK_ERROR', NETWORK_ERROR_MESSAGE)
  }
}

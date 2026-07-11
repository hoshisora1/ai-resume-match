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
    options?: ErrorOptions,
  ) {
    super(message, options)
    Object.setPrototypeOf(this, new.target.prototype)
    this.name = 'ApiError'
  }
}

export interface RequestIdCrypto {
  randomUUID?: Crypto['randomUUID'] | undefined
  getRandomValues?: Crypto['getRandomValues'] | undefined
}

function formatUuid(bytes: Uint8Array) {
  const hex = Array.from(bytes, (value) =>
    value.toString(16).padStart(2, '0'),
  ).join('')

  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-')
}

export function createRequestId(
  cryptoProvider: RequestIdCrypto | undefined = globalThis.crypto,
) {
  if (typeof cryptoProvider?.randomUUID === 'function') {
    try {
      return cryptoProvider.randomUUID()
    } catch {
      // Fall through to getRandomValues when randomUUID is unavailable at runtime.
    }
  }

  if (typeof cryptoProvider?.getRandomValues !== 'function') {
    return undefined
  }

  try {
    const bytes = new Uint8Array(16)
    cryptoProvider.getRandomValues(bytes)
    bytes[6] = (bytes[6]! & 0x0f) | 0x40
    bytes[8] = (bytes[8]! & 0x3f) | 0x80
    return formatUuid(bytes)
  } catch {
    return undefined
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
    if (error instanceof ApiError || isAbortError(error)) {
      throw error
    }
    if (error instanceof SyntaxError) {
      return null
    }
    throw error
  }
}

export async function apiRequest<T>(
  path: string,
  schema: ZodType<T>,
  init?: RequestInit,
): Promise<T> {
  let outgoingRequestId: string | undefined
  let responseRequestId: string | undefined

  try {
    const headers = new Headers(init?.headers)
    headers.set('Accept', 'application/json')
    headers.delete('X-Request-Id')
    outgoingRequestId = createRequestId()
    if (outgoingRequestId !== undefined) {
      headers.set('X-Request-Id', outgoingRequestId)
    }

    const response = await fetch(path, {
      ...init,
      headers,
    })
    responseRequestId = response.headers.get('X-Request-Id') ?? undefined
    const payload = await readJson(response)

    if (!response.ok) {
      const parsedError = apiErrorSchema.safeParse(payload)
      if (parsedError.success) {
        throw new ApiError(
          response.status,
          parsedError.data.code,
          parsedError.data.message,
          parsedError.data.requestId ?? responseRequestId ?? outgoingRequestId,
        )
      }

      throw new ApiError(
        response.status,
        'HTTP_ERROR',
        HTTP_ERROR_MESSAGE,
        responseRequestId ?? outgoingRequestId,
      )
    }

    const parsedResponse = schema.safeParse(payload)
    if (!parsedResponse.success) {
      throw new ApiError(
        response.status,
        'INVALID_RESPONSE',
        INVALID_RESPONSE_MESSAGE,
        responseRequestId ?? outgoingRequestId,
      )
    }

    return parsedResponse.data
  } catch (error) {
    if (error instanceof ApiError || isAbortError(error)) {
      throw error
    }

    throw new ApiError(
      0,
      'NETWORK_ERROR',
      NETWORK_ERROR_MESSAGE,
      responseRequestId ?? outgoingRequestId,
      { cause: error },
    )
  }
}

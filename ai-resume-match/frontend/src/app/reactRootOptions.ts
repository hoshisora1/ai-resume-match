import type { RootOptions } from 'react-dom/client'

type ReactRootErrorHandler = (error: unknown, errorInfo: unknown) => void

export const suppressReactErrorDetails: ReactRootErrorHandler = () => undefined

export const reactRootOptions = {
  onCaughtError: suppressReactErrorDetails,
  onUncaughtError: suppressReactErrorDetails,
  onRecoverableError: suppressReactErrorDetails,
} satisfies RootOptions

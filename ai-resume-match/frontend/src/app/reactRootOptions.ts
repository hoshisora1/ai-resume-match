import type { RootOptions } from 'react-dom/client'

export const suppressCaughtErrorDetails: NonNullable<
  RootOptions['onCaughtError']
> = () => undefined

export const reactRootOptions = {
  onCaughtError: suppressCaughtErrorDetails,
} satisfies RootOptions

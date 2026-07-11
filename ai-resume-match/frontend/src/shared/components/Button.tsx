import { RefreshCw } from 'lucide-react'
import { forwardRef, type ButtonHTMLAttributes } from 'react'

export type ButtonVariant = 'primary' | 'secondary' | 'danger'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  loading?: boolean
  variant?: ButtonVariant
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    children,
    className,
    disabled = false,
    loading = false,
    type = 'button',
    variant = 'primary',
    ...buttonProps
  },
  ref,
) {
  const classes = ['button', `button--${variant}`, className]
    .filter(Boolean)
    .join(' ')

  return (
    <button
      {...buttonProps}
      aria-busy={loading || undefined}
      className={classes}
      data-loading={loading}
      data-variant={variant}
      disabled={disabled || loading}
      ref={ref}
      type={type}
    >
      <span className="button__content">{children}</span>
      {loading ? (
        <RefreshCw aria-hidden="true" className="button__spinner" size={17} />
      ) : null}
    </button>
  )
})

import {
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type KeyboardEvent,
  type ReactNode,
  type Ref,
} from 'react'
import {Icon} from './Icon'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  ref?: Ref<HTMLButtonElement>
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'quiet'
  size?: 'sm' | 'md' | 'lg'
  icon?: string
  loading?: boolean
}
export function Button({
  variant = 'secondary',
  size = 'md',
  icon,
  loading,
  children,
  className,
  disabled,
  type = 'button',
  ...rest
}: ButtonProps) {
  return (
    <button
      type={type}
      className={`btn btn-${variant} btn-${size}${className ? ` ${className}` : ''}`}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Spinner /> : icon ? <Icon name={icon} size={size === 'sm' ? 15 : 17} /> : null}
      {children !== undefined && <span className="btn-label">{children}</span>}
    </button>
  )
}

export function IconButton({
  icon,
  label,
  size = 'md',
  tone,
  className,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  icon: string
  label: string
  size?: 'sm' | 'md'
  tone?: 'danger'
}) {
  return (
    <button
      type="button"
      className={`icon-btn icon-btn-${size}${tone ? ` icon-btn-${tone}` : ''}${className ? ` ${className}` : ''}`}
      aria-label={label}
      title={label}
      {...rest}
    >
      <Icon name={icon} size={size === 'sm' ? 15 : 17} />
    </button>
  )
}

export function Spinner({size = 15, label}: {size?: number; label?: string}) {
  return (
    <svg
      className="spinner"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
    >
      <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2.5" />
      <path d="M21 12a9 9 0 0 0-9-9" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" />
    </svg>
  )
}

/** Radio-group segmented control with arrow-key navigation. */
export function Segmented<T extends string>({
  value,
  options,
  onChange,
  label,
  size = 'md',
  disabled,
}: {
  value: T
  options: {value: T; label: ReactNode; disabled?: boolean; title?: string}[]
  onChange: (value: T) => void
  label: string
  size?: 'sm' | 'md'
  disabled?: boolean
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  function key(event: KeyboardEvent, index: number) {
    const step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 0
    if (!step) return
    event.preventDefault()
    for (let i = 1; i <= options.length; i++) {
      const next = (index + step * i + options.length) % options.length
      if (!options[next].disabled) {
        refs.current[next]?.focus()
        onChange(options[next].value)
        return
      }
    }
  }
  return (
    <div className={`segmented segmented-${size}`} role="radiogroup" aria-label={label}>
      {options.map((option, index) => {
        const checked = option.value === value
        return (
          <button
            key={option.value}
            ref={(element) => {
              refs.current[index] = element
            }}
            type="button"
            role="radio"
            aria-checked={checked}
            tabIndex={checked ? 0 : -1}
            title={option.title}
            disabled={disabled || option.disabled}
            className={checked ? 'checked' : undefined}
            onClick={() => onChange(option.value)}
            onKeyDown={(event) => key(event, index)}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
  id,
}: {
  checked: boolean
  onChange: (value: boolean) => void
  label: string
  disabled?: boolean
  id?: string
}) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      className="switch"
      onClick={() => onChange(!checked)}
    >
      <span className="switch-thumb" />
    </button>
  )
}

/** A settings/option row: title, description, and a trailing control. */
export function OptionRow({
  title,
  description,
  control,
  children,
  disabled,
}: {
  title: ReactNode
  description?: ReactNode
  control?: ReactNode
  children?: ReactNode
  disabled?: boolean
}) {
  return (
    <div className={`option-row${disabled ? ' is-disabled' : ''}`}>
      <div className="option-text">
        <div className="option-title">{title}</div>
        {description && <div className="option-desc">{description}</div>}
        {children}
      </div>
      {control && <div className="option-control">{control}</div>}
    </div>
  )
}

export function SwitchRow({
  title,
  description,
  checked,
  onChange,
  disabled,
}: {
  title: string
  description?: ReactNode
  checked: boolean
  onChange: (value: boolean) => void
  disabled?: boolean
}) {
  return (
    <OptionRow
      title={title}
      description={description}
      disabled={disabled}
      control={<Switch label={title} checked={checked} onChange={onChange} disabled={disabled} />}
    />
  )
}

export function Select<T extends string>({
  value,
  options,
  onChange,
  label,
  hideLabel = true,
  disabled,
  className,
}: {
  value: T
  options: {value: T; label: string; disabled?: boolean}[]
  onChange: (value: T) => void
  label: string
  hideLabel?: boolean
  disabled?: boolean
  className?: string
}) {
  const id = useId()
  return (
    <span className={`select${className ? ` ${className}` : ''}`}>
      <label htmlFor={id} className={hideLabel ? 'sr-only' : 'select-label'}>
        {label}
      </label>
      <span className="select-box">
        <select id={id} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value as T)}>
          {options.map((option) => (
            <option key={option.value} value={option.value} disabled={option.disabled}>
              {option.label}
            </option>
          ))}
        </select>
        <Icon name="chevronDown" size={14} />
      </span>
    </span>
  )
}

export function Tabs<T extends string>({
  value,
  tabs,
  onChange,
  label,
  idBase,
}: {
  value: T
  tabs: {value: T; label: ReactNode; count?: number}[]
  onChange: (value: T) => void
  label: string
  idBase: string
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([])
  return (
    <div className="tabs" role="tablist" aria-label={label}>
      {tabs.map((tab, index) => (
        <button
          key={tab.value}
          ref={(element) => {
            refs.current[index] = element
          }}
          type="button"
          role="tab"
          id={`${idBase}-tab-${tab.value}`}
          aria-controls={`${idBase}-panel`}
          aria-selected={tab.value === value}
          tabIndex={tab.value === value ? 0 : -1}
          className={tab.value === value ? 'selected' : undefined}
          onClick={() => onChange(tab.value)}
          onKeyDown={(event) => {
            const step = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
            if (!step) return
            event.preventDefault()
            const next = (index + step + tabs.length) % tabs.length
            refs.current[next]?.focus()
            onChange(tabs[next].value)
          }}
        >
          {tab.label}
          {tab.count !== undefined && <span className="tab-count">{tab.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function Badge({
  children,
  tone = 'neutral',
  title,
}: {
  children: ReactNode
  tone?: 'neutral' | 'accent' | 'hdr' | 'success' | 'danger' | 'warning' | 'solid'
  title?: string
}) {
  return (
    <span className={`badge badge-${tone}`} title={title}>
      {children}
    </span>
  )
}

export function ProgressBar({
  value,
  label,
  mode = 'determinate',
  tone,
}: {
  value: number
  label: string
  mode?: 'determinate' | 'indeterminate'
  tone?: 'paused' | 'processing'
}) {
  const clamped = Math.max(0, Math.min(100, value || 0))
  return (
    <div
      className={`progress progress-${mode}${tone ? ` progress-${tone}` : ''}`}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={mode === 'determinate' ? Math.round(clamped) : undefined}
    >
      <div className="progress-fill" style={mode === 'determinate' ? {transform: `scaleX(${clamped / 100})`} : undefined} />
    </div>
  )
}

export function Kbd({children}: {children: ReactNode}) {
  return <kbd className="kbd">{children}</kbd>
}

/** Destructive action that asks again in place; nothing happens until the second, explicit click. */
export function ConfirmInline({
  label,
  confirmLabel,
  prompt,
  onConfirm,
  icon,
  disabled,
  size = 'sm',
}: {
  label: string
  confirmLabel: string
  prompt: ReactNode
  onConfirm: () => Promise<unknown> | void
  icon?: string
  disabled?: boolean
  size?: 'sm' | 'md'
}) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const first = useRef<HTMLButtonElement>(null)
  if (!open)
    return (
      <Button variant="ghost" size={size} icon={icon} disabled={disabled} onClick={() => {
        setOpen(true)
        requestAnimationFrame(() => first.current?.focus())
      }}>
        {label}
      </Button>
    )
  return (
    <div className="confirm-inline" role="group" aria-label={label}>
      <span className="confirm-prompt">{prompt}</span>
      <button ref={first} type="button" className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>
        <span className="btn-label">Keep</span>
      </button>
      <Button
        variant="danger"
        size="sm"
        loading={busy}
        onClick={async () => {
          setBusy(true)
          try {
            await onConfirm()
          } finally {
            setBusy(false)
            setOpen(false)
          }
        }}
      >
        {confirmLabel}
      </Button>
    </div>
  )
}

export function EmptyState({
  icon,
  title,
  children,
  actions,
}: {
  icon: string
  title: string
  children?: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Icon name={icon} size={22} />
      </span>
      <div className="empty-title">{title}</div>
      {children && <div className="empty-body">{children}</div>}
      {actions && <div className="empty-actions">{actions}</div>}
    </div>
  )
}

export function InlineAlert({
  tone = 'error',
  title,
  children,
  actions,
}: {
  tone?: 'error' | 'warning' | 'info'
  title?: string
  children: ReactNode
  actions?: ReactNode
}) {
  return (
    <div className={`inline-alert inline-alert-${tone}`} role={tone === 'info' ? 'status' : 'alert'}>
      <Icon name={tone === 'info' ? 'info' : 'warning'} size={17} />
      <div className="inline-alert-body">
        {title && <strong>{title}</strong>}
        <span>{children}</span>
      </div>
      {actions && <div className="inline-alert-actions">{actions}</div>}
    </div>
  )
}

export function Stepper({
  value,
  min,
  max,
  onChange,
  label,
  disabled,
  suffix,
}: {
  value: number
  min: number
  max: number
  onChange: (value: number) => void
  label: string
  disabled?: boolean
  suffix?: string
}) {
  const clamp = (next: number) => Math.max(min, Math.min(max, next))
  return (
    <div
      className="stepper"
      role="spinbutton"
      tabIndex={disabled ? -1 : 0}
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      aria-disabled={disabled || undefined}
      onKeyDown={(event) => {
        if (disabled) return
        const step = event.key === 'ArrowUp' || event.key === 'ArrowRight' ? 1 : event.key === 'ArrowDown' || event.key === 'ArrowLeft' ? -1 : 0
        if (step) {
          event.preventDefault()
          onChange(clamp(value + step))
        } else if (event.key === 'Home') {
          event.preventDefault()
          onChange(min)
        } else if (event.key === 'End') {
          event.preventDefault()
          onChange(max)
        }
      }}
    >
      <button type="button" tabIndex={-1} aria-hidden="true" disabled={disabled || value <= min} onClick={() => onChange(clamp(value - 1))}>
        <span className="stepper-sign">−</span>
      </button>
      <span className="stepper-value tabular">
        {value}
        {suffix && <span className="stepper-suffix">{suffix}</span>}
      </span>
      <button type="button" tabIndex={-1} aria-hidden="true" disabled={disabled || value >= max} onClick={() => onChange(clamp(value + 1))}>
        <span className="stepper-sign">+</span>
      </button>
    </div>
  )
}

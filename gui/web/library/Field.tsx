import type {ReactNode} from 'react'

export function Field({label, help, error, children, htmlFor}: {label: string; help?: string; error?: string; children: ReactNode; htmlFor?: string}) {
  return (
    <div className="field-block">
      <label className="field-label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error ? (
        <p className="field-error" role="alert">
          {error}
        </p>
      ) : (
        help && <p className="field-help">{help}</p>
      )}
    </div>
  )
}

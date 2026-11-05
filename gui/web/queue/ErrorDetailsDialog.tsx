import type {Job} from '../../shared/contracts'
import {toast} from '../toasts'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'

/** Full failure text (tool names already removed) so users can report exactly what happened. */
export function ErrorDetailsDialog({job, onClose}: {job: Job; onClose: () => void}) {
  const text = [job.error, job.errorDetail && job.errorDetail !== job.error ? job.errorDetail : '']
    .filter(Boolean)
    .join('\n\n')
  const content = text || 'The download stopped unexpectedly.'
  return (
    <Dialog
      title="Error details"
      labelledBy={`error-${job.id}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} autoFocus>
            Close
          </Button>
          <Button
            icon="copy"
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(`${job.title}\n${job.url}\n\n${content}`)
                toast({tone: 'success', message: 'Error details copied.'})
              } catch {
                toast({tone: 'error', message: 'Could not copy. Select the text and copy it manually.'})
              }
            }}
          >
            Copy
          </Button>
        </>
      }
    >
      <pre className="error-details" tabIndex={0}>
        {content}
      </pre>
    </Dialog>
  )
}

import {useState} from 'react'
import type {Job} from '../../shared/contracts'
import {fileName, formatBytes} from '../format'
import {act} from '../store'
import {toast} from '../toasts'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'

/** Deleting media from disk always names the exact file and needs a deliberate second click. */
export function DeleteFileDialog({job, onClose}: {job: Job; onClose: () => void}) {
  const [busy, setBusy] = useState(false)
  const name = fileName(job.file)
  const extra = job.files?.length || 0
  return (
    <Dialog
      title="Delete file from disk?"
      labelledBy={`delete-${job.id}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} autoFocus>
            Keep file
          </Button>
          <Button
            variant="danger"
            icon="trash"
            loading={busy}
            onClick={async () => {
              setBusy(true)
              const ok = await act('deleteFile', {id: job.id})
              setBusy(false)
              if (ok) {
                toast({tone: 'success', message: `Deleted ${name}.`})
                onClose()
              }
            }}
          >
            Delete file
          </Button>
        </>
      }
    >
      <p className="delete-name">{name}</p>
      <p className="muted small">
        {[formatBytes(job.fileSize), job.file].filter(Boolean).join(' · ')}
      </p>
      <p>
        This removes the file{extra ? ` and ${extra} related ${extra === 1 ? 'file' : 'files'}` : ''} from your computer. Keepfin
        can’t undo it.
      </p>
    </Dialog>
  )
}

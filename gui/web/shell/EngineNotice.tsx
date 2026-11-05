import {useState} from 'react'
import type {EngineStatus} from '../../shared/contracts'
import {scrubToolNames} from '../../shared/errors'
import {messageOf, native, request} from '../bridge'
import {navigate} from '../nav'
import {setEngine, useAggregate, useMeta} from '../store'
import {toast} from '../toasts'
import {Button, InlineAlert, Spinner} from '../ui/controls'
import {needsFixing, toolLabels} from '../toolNames'

/**
 * The download engine only appears on main surfaces when it matters: while a job waits for an
 * update, during first-run installation, or when it is broken (with a one-click fix).
 * Versions and routine update state live in Settings → Updates.
 */
export function EngineNotice() {
  const {engine, ready} = useMeta()
  const {engineWaiting} = useAggregate()
  const [fixing, setFixing] = useState(false)
  if (!native || !ready) return null
  const broken = engine.tools.find(needsFixing)
  const installing = engine.tools.some((tool) => tool.state === 'installing')
  const busy = engine.tools.some((tool) => tool.state === 'updating' || tool.state === 'installing')
  async function fix() {
    setFixing(true)
    try {
      setEngine(await request<EngineStatus>({op: 'engineUpdate'}))
    } catch (error) {
      toast({tone: 'error', message: messageOf(error)})
    } finally {
      setFixing(false)
    }
  }
  if (engineWaiting > 0 || installing || (busy && fixing))
    return (
      <p className="engine-notice" role="status">
        <Spinner size={13} />
        {installing && !engineWaiting ? 'Setting up the download engine…' : 'Updating download engine…'}
      </p>
    )
  if (!broken) return null
  return (
    <InlineAlert
      tone="warning"
      title="The download engine needs attention"
      actions={
        <>
          <Button size="sm" icon="refresh" loading={fixing} onClick={() => void fix()}>
            Fix
          </Button>
          <Button size="sm" variant="ghost" onClick={() => navigate('Settings', 'updates')}>
            Details
          </Button>
        </>
      }
    >
      {broken.state === 'missing' ? `${toolLabels[broken.name]} is missing.` : scrubToolNames(broken.message || `${toolLabels[broken.name]} is not working.`)}
    </InlineAlert>
  )
}

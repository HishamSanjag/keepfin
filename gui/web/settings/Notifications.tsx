import {useEffect, useState} from 'react'
import {
  notificationDefaults,
  type NotificationPreferences,
  type NotificationState,
} from '../../shared/notifications'
import {messageOf, native, request} from '../bridge'
import {Button, InlineAlert, Switch} from '../ui/controls'
import {Group, SettingCard} from '../ui/SettingCard'

const options: [keyof NotificationPreferences, string, string, string][] = [
  ['enabled', 'System notifications', 'Get alerts when downloads finish or need attention.', 'bell'],
  ['completed', 'Completed downloads', 'Click the notification to show the saved file.', 'check'],
  ['failed', 'Download failures', 'Opens the app to review the error and retry.', 'warning'],
  ['sound', 'Notification sounds', 'Uses the system alert sound. Quiet modes still apply.', 'volume'],
  ['progress', 'Taskbar progress', 'Shows progress on the taskbar icon while the app is minimized.', 'speed'],
]

export function Notifications() {
  const [state, setState] = useState<NotificationState>()
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [pending, setPending] = useState(false)
  useEffect(() => {
    if (!native) return
    let disposed = false
    const poll = () =>
      request<NotificationState>({op: 'notificationState'})
        .then((next) => {
          if (!disposed)
            setState((previous) => (previous ? {...previous, supported: next.supported, lastError: next.lastError} : next))
        })
        .catch((e) => !disposed && setError(messageOf(e)))
    void poll()
    const timer = setInterval(poll, 2000)
    return () => {
      disposed = true
      clearInterval(timer)
    }
  }, [])
  async function run(op: 'notificationConfigure' | 'notificationTest' | 'notificationSettings', preferences?: NotificationPreferences) {
    setPending(true)
    setError('')
    setNotice('')
    const previous = state
    if (op === 'notificationConfigure' && preferences && state) setState({...state, preferences})
    try {
      const next = await request<NotificationState>({op, notifications: preferences})
      setState(next)
      if (op === 'notificationTest') setNotice('Test sent. If no banner appears, check Windows notification settings or Do not disturb.')
    } catch (e) {
      if (op === 'notificationConfigure') setState(previous)
      setError(messageOf(e))
    } finally {
      setPending(false)
    }
  }
  const preferences = state?.preferences || notificationDefaults
  return (
    <>
      <Group cards title="Alerts" description="Alerts are sent while the app is running. Closing the app stops downloads.">
        {options.map(([key, title, description, icon]) => {
          const off = !state || pending || (key !== 'enabled' && !preferences.enabled)
          return (
            <SettingCard
              key={key}
              anchor={`notify-${key}`}
              icon={icon}
              title={title}
              description={description}
              disabled={key !== 'enabled' && !preferences.enabled}
              control={<Switch label={title} checked={preferences[key]} disabled={off} onChange={(value) => void run('notificationConfigure', {...preferences, [key]: value})} />}
            />
          )
        })}
      </Group>
      {!native && (
        <InlineAlert tone="info">System notification controls are available in the installed desktop app.</InlineAlert>
      )}
      {state && !state.supported && (
        <InlineAlert tone="warning">System notifications are unavailable on this device. Taskbar progress still works.</InlineAlert>
      )}
      {(error || state?.lastError) && <InlineAlert>{error || state?.lastError}</InlineAlert>}
      {notice && <InlineAlert tone="info">{notice}</InlineAlert>}
      {native && (
        <div className="button-row">
          <Button disabled={!state || !state.supported || pending || !preferences.enabled} onClick={() => void run('notificationTest')}>
            Send test notification
          </Button>
          <Button variant="ghost" icon="external" disabled={pending} onClick={() => void run('notificationSettings')}>
            Windows notification settings
          </Button>
        </div>
      )}
    </>
  )
}

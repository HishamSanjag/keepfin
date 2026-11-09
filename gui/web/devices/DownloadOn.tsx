import {useState} from 'react'
import type {DownloadOptions, DownloadPreset, FormatRequest} from '../../shared/contracts'
import {messageOf} from '../bridge'
import {useDevices} from '../settings/devices/useDevices'
import {sendJob} from '../settings/devices/devicesApi'
import {toast} from '../toasts'
import {Menu, type MenuItem} from '../ui/Menu'

/** The quality presets of a collection, as the format the other device is asked for (the exact stream ids stay on the device that inspects the link). */
export function presetToFormat(preset: DownloadPreset): FormatRequest {
  if (preset.startsWith('audio-')) return {type: 'audio', target: preset.slice(6) as 'mp3' | 'm4a' | 'opus' | 'flac', quality: 'best'}
  return {type: 'video', codec: 'auto', container: 'mp4', ...(preset === 'best' ? {} : {maxHeight: Number(preset)})}
}

type Job = {url: string; format: FormatRequest; options?: DownloadOptions; /** The music video box is ticked: the other device keeps the song's video too. */ musicVideo?: boolean}

/**
 * "Download on…": the devices that let this one send them downloads. The other device checks the link, picks its own streams and saves
 * where its own rules say (or in the space chosen here); its progress is shown with the downloads. Renders nothing when no device allows it.
 */
export function DownloadOn({jobs, label = 'Download on…', onSent, disabled}: {jobs: () => Job[]; label?: string; onSent?: (count: number, device: string) => void; disabled?: boolean}) {
  const {state} = useDevices()
  const [busy, setBusy] = useState(false)
  const devices = (state?.paired ?? []).filter((device) => device.online && device.jobs.allowed)
  if (!devices.length) return null
  if (disabled)
    return (
      <button type="button" className="menu-trigger download-on" disabled aria-label={label}>
        <span>{label}</span>
      </button>
    )

  async function send(deviceId: string, name: string, space?: string) {
    const list = jobs()
    if (!list.length || busy) return
    setBusy(true)
    try {
      for (const job of list) await sendJob(deviceId, {url: job.url, format: job.format, options: job.options, space, ...(job.musicVideo ? {musicVideo: true} : {})})
      toast({tone: 'success', message: list.length === 1 ? `Sent to ${name}. Its progress shows in Downloads.` : `Sent ${list.length.toLocaleString()} downloads to ${name}. Their progress shows in Downloads.`})
      onSent?.(list.length, name)
    } catch (caught) {
      toast({tone: 'error', message: messageOf(caught)})
    } finally {
      setBusy(false)
    }
  }

  const items: MenuItem[] = []
  for (const device of devices) {
    if (devices.length > 1 || device.jobs.spaces.length > 1) items.push({heading: device.name})
    items.push({label: device.jobs.spaces.length > 1 ? `${device.name} · its usual place` : device.name, icon: 'download', onSelect: () => void send(device.deviceId, device.name)})
    if (device.jobs.spaces.length > 1) for (const space of device.jobs.spaces) items.push({label: `${device.name} · ${space.name}`, onSelect: () => void send(device.deviceId, device.name, space.id)})
  }
  return <Menu label={label} items={items} trigger={<span>{busy ? 'Sending…' : label}</span>} triggerClassName="download-on" align="start" width={260} />
}

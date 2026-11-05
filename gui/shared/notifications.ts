export type NotificationPreferences = {
  enabled: boolean
  completed: boolean
  failed: boolean
  sound: boolean
  progress: boolean
}
export const notificationDefaults: NotificationPreferences = {
  enabled: true,
  completed: true,
  failed: true,
  sound: false,
  progress: true,
}
export type NotificationState = {
  preferences: NotificationPreferences
  supported: boolean
  lastError?: string
}
export function notificationPreferences(
  value: unknown,
): NotificationPreferences {
  const result = {...notificationDefaults}
  if (!value || typeof value !== 'object' || Array.isArray(value)) return result
  for (const key of Object.keys(result) as (keyof NotificationPreferences)[])
    if (typeof (value as Record<string, unknown>)[key] === 'boolean')
      result[key] = (value as NotificationPreferences)[key]
  return result
}

import {contextBridge, ipcRenderer, webUtils} from 'electron'
contextBridge.exposeInMainWorld('keepfin', {
  request: (request: unknown) => ipcRenderer.invoke('keepfin:request', request),
  onPush: (callback: (event: unknown) => void) => {
    if (typeof callback !== 'function')
      throw new TypeError('onPush needs a function.')
    const listener = (_event: unknown, payload: unknown) => callback(payload)
    ipcRenderer.on('keepfin:push', listener)
    return () => {
      ipcRenderer.removeListener('keepfin:push', listener)
    }
  },
  /** Real path of a file dropped onto the window (Add files by drag and drop); main re-validates it. */
  pathForFile: (file: File) => webUtils.getPathForFile(file),
})

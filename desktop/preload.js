const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('phoneix', {
  state: () => ipcRenderer.invoke('state'),
  pair: () => ipcRenderer.invoke('pair'),
  cancel: () => ipcRenderer.send('cancel'),
  unpair: () => ipcRenderer.send('unpair'),
  test: () => ipcRenderer.invoke('test'),
  autostart: (on) => ipcRenderer.send('autostart', on),
  onState: (cb) => ipcRenderer.on('state', (_e, s) => cb(s)),
  onPaired: (cb) => ipcRenderer.on('paired', () => cb())
});

const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('frame', {
  onState: (cb) => ipcRenderer.on('state', (_e, p) => cb(p)),
  close: () => ipcRenderer.send('frame:close'),
  drag: (k, x, y) => ipcRenderer.send('frame:drag', k, x, y)
});

const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('callApi', {
  action: (a) => ipcRenderer.send('call:action', a),
  onCall: (cb) => ipcRenderer.on('call', (_e, s) => cb(s)),
  onRing: (cb) => ipcRenderer.on('ring', (_e, r) => cb(r))
});

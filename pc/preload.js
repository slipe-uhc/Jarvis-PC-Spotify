const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('Desktop', {
  startVoice: () => ipcRenderer.send('voice:start'),
  stopVoice: () => ipcRenderer.send('voice:stop'),
  openUrl: (u) => ipcRenderer.send('open', u),
  openApp: (n, u) => ipcRenderer.send('openApp', n, u),
  onHeard: (cb) => ipcRenderer.on('heard', (e, t) => cb(t)),
  onStatus: (cb) => ipcRenderer.on('status', (e, t) => cb(t))
});

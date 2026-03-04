const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sqlCopilotDesktop', {
  pickFile(options = {}) {
    return ipcRenderer.invoke('dialog:pick-file', options);
  },
  pickDirectory(options = {}) {
    return ipcRenderer.invoke('dialog:pick-directory', options);
  },
  saveChartCache(payload = {}) {
    return ipcRenderer.invoke('chart-cache:save', payload);
  },
  readChartCache(filePath = '') {
    return ipcRenderer.invoke('chart-cache:read', filePath);
  },
});

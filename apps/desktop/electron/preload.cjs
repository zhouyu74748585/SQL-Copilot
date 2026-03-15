const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sqlCopilotDesktop', {
  pickFile(options = {}) {
    return ipcRenderer.invoke('dialog:pick-file', options);
  },
  pickDirectory(options = {}) {
    return ipcRenderer.invoke('dialog:pick-directory', options);
  },
  openExternal(url = '') {
    return ipcRenderer.invoke('shell:open-external', url);
  },
  openPrivacyPolicy() {
    return ipcRenderer.invoke('shell:open-privacy-policy');
  },
  saveChartCache(payload = {}) {
    return ipcRenderer.invoke('chart-cache:save', payload);
  },
  readChartCache(filePath = '') {
    return ipcRenderer.invoke('chart-cache:read', filePath);
  },
});

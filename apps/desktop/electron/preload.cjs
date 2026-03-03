const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sqlCopilotDesktop', {
  pickFile(options = {}) {
    return ipcRenderer.invoke('dialog:pick-file', options);
  },
  pickDirectory(options = {}) {
    return ipcRenderer.invoke('dialog:pick-directory', options);
  },
});

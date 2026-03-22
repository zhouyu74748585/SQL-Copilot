const { contextBridge, ipcRenderer } = require('electron');

function resolveBackendBaseUrl() {
  const raw = process.env.SQLCOPILOT_RENDERER_BACKEND_URL
    || process.env.SQLCOPILOT_BACKEND_URL
    || 'http://localhost:18080';
  return String(raw).trim().replace(/\/+$/, '') || 'http://localhost:18080';
}

const backendBaseUrl = resolveBackendBaseUrl();

contextBridge.exposeInMainWorld('sqlCopilotDesktop', {
  backendBaseUrl,
  getBackendBaseUrl() {
    return backendBaseUrl;
  },
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
  setUiTheme(theme = 'light') {
    return ipcRenderer.invoke('window:set-ui-theme', theme);
  },
  saveChartCache(payload = {}) {
    return ipcRenderer.invoke('chart-cache:save', payload);
  },
  readChartCache(filePath = '') {
    return ipcRenderer.invoke('chart-cache:read', filePath);
  },
});

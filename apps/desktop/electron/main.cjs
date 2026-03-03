const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

let qdrantProcess = null;

function createWindow() {
  const rendererUrl = process.env.ELECTRON_RENDERER_URL;
  const isDebug = process.env.ELECTRON_DEBUG === '1';
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    title: 'SQL Copilot',
    autoHideMenuBar: true,
    titleBarStyle: process.platform === 'darwin' ? 'hidden' : 'default',
    backgroundColor: '#f8faff',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  if (rendererUrl) {
    win.loadURL(rendererUrl);
  } else {
    const indexPath = path.join(__dirname, '../dist/index.html');
    win.loadFile(indexPath);
  }

  if (isDebug) {
    win.webContents.openDevTools({ mode: 'detach' });
  }
}

function normalizeDialogFilters(filters) {
  if (!Array.isArray(filters) || filters.length === 0) {
    return undefined;
  }
  return filters
    .map((item) => {
      const name = typeof item?.name === 'string' ? item.name.trim() : '';
      const extensions = Array.isArray(item?.extensions)
        ? item.extensions
            .map((ext) => (typeof ext === 'string' ? ext.replace(/^\./, '').trim() : ''))
            .filter((ext) => !!ext)
        : [];
      if (!name || !extensions.length) {
        return null;
      }
      return { name, extensions };
    })
    .filter((item) => !!item);
}

function registerIpcHandlers() {
  ipcMain.handle('dialog:pick-file', async (_event, rawOptions) => {
    const options = rawOptions && typeof rawOptions === 'object' ? rawOptions : {};
    const title = typeof options.title === 'string' && options.title.trim()
      ? options.title.trim()
      : 'Select file';
    const defaultPath = typeof options.defaultPath === 'string' && options.defaultPath.trim()
      ? options.defaultPath.trim()
      : undefined;
    const filters = normalizeDialogFilters(options.filters);
    const parent = BrowserWindow.getFocusedWindow() || undefined;
    const result = await dialog.showOpenDialog(parent, {
      title,
      defaultPath,
      filters,
      properties: ['openFile'],
    });
    if (result.canceled || !Array.isArray(result.filePaths) || !result.filePaths.length) {
      return '';
    }
    return result.filePaths[0];
  });

  ipcMain.handle('dialog:pick-directory', async (_event, rawOptions) => {
    const options = rawOptions && typeof rawOptions === 'object' ? rawOptions : {};
    const title = typeof options.title === 'string' && options.title.trim()
      ? options.title.trim()
      : 'Select directory';
    const defaultPath = typeof options.defaultPath === 'string' && options.defaultPath.trim()
      ? options.defaultPath.trim()
      : undefined;
    const parent = BrowserWindow.getFocusedWindow() || undefined;
    const result = await dialog.showOpenDialog(parent, {
      title,
      defaultPath,
      properties: ['openDirectory'],
    });
    if (result.canceled || !Array.isArray(result.filePaths) || !result.filePaths.length) {
      return '';
    }
    return result.filePaths[0];
  });
}

function platformKey() {
  const arch = process.arch;
  const platform = process.platform;
  if (platform === 'darwin') {
    return arch === 'arm64' ? 'darwin-arm64' : 'darwin-x64';
  }
  if (platform === 'win32') {
    return arch === 'arm64' ? 'win32-arm64' : 'win32-x64';
  }
  return arch === 'arm64' ? 'linux-arm64' : 'linux-x64';
}

function qdrantBinaryName() {
  return process.platform === 'win32' ? 'qdrant.exe' : 'qdrant';
}

function resolveQdrantBinaryPath() {
  if (process.env.QDRANT_BINARY_PATH && process.env.QDRANT_BINARY_PATH.trim()) {
    return process.env.QDRANT_BINARY_PATH.trim();
  }

  const baseDir = app.isPackaged
    ? path.join(process.resourcesPath, 'qdrant', platformKey())
    : path.join(__dirname, '../resources/qdrant', platformKey());
  return path.join(baseDir, qdrantBinaryName());
}

function parseQdrantUrl() {
  const raw = process.env.QDRANT_URL || 'http://127.0.0.1:6333';
  const parsed = new URL(raw);
  return {
    baseUrl: `${parsed.protocol}//${parsed.hostname}:${parsed.port || '6333'}`,
    host: parsed.hostname,
    httpPort: Number(parsed.port || 6333),
  };
}

function ensureExecutable(filePath) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Qdrant binary not found: ${filePath}`);
  }
  if (process.platform !== 'win32') {
    fs.chmodSync(filePath, 0o755);
  }
}

function waitForQdrantReady(baseUrl, timeoutMs = 20_000) {
  const startedAt = Date.now();

  return new Promise((resolve, reject) => {
    const probe = () => {
      const req = http.get(`${baseUrl}/collections`, (res) => {
        if (res.statusCode && res.statusCode >= 200 && res.statusCode < 500) {
          resolve();
          return;
        }
        retry();
      });
      req.on('error', retry);
    };

    const retry = () => {
      if (Date.now() - startedAt > timeoutMs) {
        reject(new Error('Qdrant startup timeout'));
        return;
      }
      setTimeout(probe, 300);
    };

    probe();
  });
}

async function startQdrant() {
  const qdrantPath = resolveQdrantBinaryPath();
  ensureExecutable(qdrantPath);

  const { baseUrl, host, httpPort } = parseQdrantUrl();
  const grpcPort = Number(process.env.QDRANT_GRPC_PORT || 6334);

  const qdrantDataDir = path.join(app.getPath('userData'), 'qdrant');
  const storageDir = path.join(qdrantDataDir, 'storage');
  const snapshotsDir = path.join(qdrantDataDir, 'snapshots');
  fs.mkdirSync(storageDir, { recursive: true });
  fs.mkdirSync(snapshotsDir, { recursive: true });

  const childEnv = {
    ...process.env,
    QDRANT__SERVICE__HOST: host,
    QDRANT__SERVICE__HTTP_PORT: String(httpPort),
    QDRANT__SERVICE__GRPC_PORT: String(grpcPort),
    QDRANT__STORAGE__STORAGE_PATH: storageDir,
    QDRANT__STORAGE__SNAPSHOTS_PATH: snapshotsDir,
  };

  qdrantProcess = spawn(qdrantPath, [], {
    env: childEnv,
    stdio: 'pipe',
  });

  qdrantProcess.stdout?.on('data', (chunk) => {
    process.stdout.write(`[qdrant] ${chunk}`);
  });
  qdrantProcess.stderr?.on('data', (chunk) => {
    process.stderr.write(`[qdrant] ${chunk}`);
  });
  qdrantProcess.on('exit', (code, signal) => {
    console.log(`[qdrant] exited code=${code}, signal=${signal}`);
    qdrantProcess = null;
  });

  await waitForQdrantReady(baseUrl);
  console.log(`[qdrant] ready at ${baseUrl}`);
}

function stopQdrant() {
  if (!qdrantProcess) {
    return;
  }

  const proc = qdrantProcess;
  qdrantProcess = null;

  try {
    proc.kill('SIGTERM');
  } catch (error) {
    console.warn(`[qdrant] failed to send SIGTERM: ${error.message}`);
  }

  setTimeout(() => {
    if (!proc.killed) {
      try {
        proc.kill('SIGKILL');
      } catch (error) {
        console.warn(`[qdrant] failed to send SIGKILL: ${error.message}`);
      }
    }
  }, 3000);
}

app.on('before-quit', stopQdrant);
app.on('will-quit', stopQdrant);

app.whenReady().then(async () => {
  registerIpcHandlers();
  try {
    await startQdrant();
  } catch (error) {
    console.error(`[qdrant] startup failed: ${error.message}`);
  }
  createWindow();
});


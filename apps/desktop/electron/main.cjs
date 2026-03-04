const { app, BrowserWindow, dialog, ipcMain } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

let qdrantProcess = null;
const MAX_CHART_CACHE_BYTES = 20 * 1024 * 1024;

function createWindow() {
  const rendererUrl = process.env.ELECTRON_RENDERER_URL;
  const isDebug = process.env.ELECTRON_DEBUG === '1';
  const isMac = process.platform === 'darwin';
  const useTitleBarOverlay = process.platform === 'win32' || process.platform === 'linux';
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    title: 'SQL Copilot',
    autoHideMenuBar: true,
    titleBarStyle: 'hidden',
    ...(useTitleBarOverlay
      ? {
          titleBarOverlay: {
            color: '#00000000',
            height: 35,
          },
        }
      : {}),
    backgroundColor: '#f8faff',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      preload: path.join(__dirname, 'preload.cjs'),
    },
  });

  if (isMac && typeof win.setWindowButtonVisibility === 'function') {
    win.setWindowButtonVisibility(true);
  }

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

function sanitizePathSegment(value) {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!normalized) {
    return '';
  }
  return normalized
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\.\./g, '_')
    .replace(/\s+/g, '_');
}

function sanitizeFileName(value) {
  const normalized = sanitizePathSegment(value);
  if (!normalized) {
    return '';
  }
  return normalized.slice(0, 80);
}

function resolveChartCacheBaseDir() {
  return path.resolve(app.getPath('userData'), 'chart-cache');
}

function toPngDataUrl(bytes) {
  return `data:image/png;base64,${bytes.toString('base64')}`;
}

function isSubPath(baseDir, targetPath) {
  const relative = path.relative(baseDir, targetPath);
  return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
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

  ipcMain.handle('chart-cache:save', async (_event, rawPayload) => {
    const payload = rawPayload && typeof rawPayload === 'object' ? rawPayload : {};
    const connectionId = Number(payload.connectionId);
    if (!Number.isFinite(connectionId) || connectionId <= 0) {
      throw new Error('连接 ID 非法');
    }

    const sessionId = sanitizePathSegment(payload.sessionId);
    if (!sessionId) {
      throw new Error('会话 ID 不能为空');
    }

    let imageBase64 = typeof payload.imageBase64Png === 'string' ? payload.imageBase64Png.trim() : '';
    if (!imageBase64) {
      throw new Error('图表图片不能为空');
    }
    if (imageBase64.startsWith('data:image/png;base64,')) {
      imageBase64 = imageBase64.slice('data:image/png;base64,'.length);
    }
    imageBase64 = imageBase64.replace(/\s+/g, '');
    if (!/^[A-Za-z0-9+/=]+$/.test(imageBase64)) {
      throw new Error('图表图片 Base64 内容无效');
    }

    let imageBytes;
    try {
      imageBytes = Buffer.from(imageBase64, 'base64');
    } catch (error) {
      throw new Error('图表图片 Base64 内容无效');
    }
    if (!imageBytes.length) {
      throw new Error('图表图片内容为空');
    }
    if (imageBytes.length > MAX_CHART_CACHE_BYTES) {
      throw new Error('图表图片超过大小限制（20MB）');
    }

    const safeFileName = sanitizeFileName(payload.suggestedFileName)
      || `chart-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
    const baseDir = resolveChartCacheBaseDir();
    const relativePath = path.join(String(connectionId), sessionId, `${safeFileName}.png`);
    const targetPath = path.resolve(baseDir, relativePath);
    if (!isSubPath(baseDir, targetPath)) {
      throw new Error('图表缓存路径非法');
    }

    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
    fs.writeFileSync(targetPath, imageBytes);

    return {
      filePath: targetPath,
      width: Number(payload.width) || 0,
      height: Number(payload.height) || 0,
    };
  });

  ipcMain.handle('chart-cache:read', async (_event, rawFilePath) => {
    const filePath = typeof rawFilePath === 'string' ? rawFilePath.trim() : '';
    if (!filePath) {
      throw new Error('图片路径不能为空');
    }
    const baseDir = resolveChartCacheBaseDir();
    const targetPath = path.resolve(filePath);
    if (!isSubPath(baseDir, targetPath)) {
      throw new Error('图片路径非法');
    }
    if (!fs.existsSync(targetPath)) {
      throw new Error('缓存图表不存在');
    }
    const imageBytes = fs.readFileSync(targetPath);
    return toPngDataUrl(imageBytes);
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

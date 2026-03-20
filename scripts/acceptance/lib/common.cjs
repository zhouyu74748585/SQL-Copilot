const fs = require('fs');
const net = require('net');
const path = require('path');
const {spawnSync} = require('child_process');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const DESKTOP_DIR = path.join(ROOT, 'apps', 'desktop');
const SERVER_DIR = path.join(ROOT, 'apps', 'server');
const OUTPUT_ROOT = path.join(ROOT, 'output', 'playwright');
const BACKEND_URL = 'http://127.0.0.1:18080';
const PREVIEW_URL = 'http://127.0.0.1:8888';
const BACKEND_HEALTH_URL = `${BACKEND_URL}/api/health`;

function parseArgs(argv) {
  const args = {};
  argv.forEach((entry) => {
    if (!entry.startsWith('--')) {
      return;
    }
    const raw = entry.slice(2);
    const separator = raw.indexOf('=');
    if (separator < 0) {
      args[raw] = 'true';
      return;
    }
    args[raw.slice(0, separator)] = raw.slice(separator + 1);
  });
  return args;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(check, options = {}) {
  const timeoutMs = options.timeoutMs ?? 60_000;
  const intervalMs = options.intervalMs ?? 300;
  const timeoutMessage = options.timeoutMessage ?? 'Condition timed out.';
  const startedAt = Date.now();
  let lastError = null;

  while (Date.now() - startedAt < timeoutMs) {
    try {
      const value = await check();
      if (value) {
        return value;
      }
    } catch (error) {
      lastError = error;
    }
    await sleep(intervalMs);
  }

  if (lastError) {
    throw lastError;
  }
  throw new Error(timeoutMessage);
}

async function waitForUrl(url, options = {}) {
  return waitFor(async () => {
    const response = await fetch(url, {redirect: 'follow'});
    if (!response.ok) {
      return false;
    }
    const body = await response.text();
    const validate = options.validate ?? (() => true);
    if (!validate(body, response)) {
      return false;
    }
    return {body, status: response.status};
  }, {
    timeoutMs: options.timeoutMs ?? 60_000,
    intervalMs: options.intervalMs ?? 300,
    timeoutMessage: options.timeoutMessage ?? `Timed out waiting for ${url}`,
  });
}

async function apiRequest(baseUrl, method, requestPath, payload) {
  const response = await fetch(`${baseUrl}${requestPath}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
    },
    body: payload == null ? undefined : JSON.stringify(payload),
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (error) {
    throw new Error(`Invalid JSON from ${requestPath}: ${text || response.statusText}`);
  }
  if (!response.ok || json?.code !== 0) {
    throw new Error(json?.message || `HTTP ${response.status}`);
  }
  return json.data;
}

function ensureDir(dirPath) {
  fs.mkdirSync(dirPath, {recursive: true});
}

function resetDir(dirPath) {
  fs.rmSync(dirPath, {recursive: true, force: true});
  ensureDir(dirPath);
}

function writeJson(filePath, data) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
}

function writeText(filePath, content) {
  ensureDir(path.dirname(filePath));
  fs.writeFileSync(filePath, content, 'utf8');
}

function readJsonIfExists(filePath) {
  if (!fs.existsSync(filePath)) {
    return null;
  }
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function nowTimestamp() {
  const date = new Date();
  const y = date.getFullYear();
  const m = `${date.getMonth() + 1}`.padStart(2, '0');
  const d = `${date.getDate()}`.padStart(2, '0');
  const hh = `${date.getHours()}`.padStart(2, '0');
  const mm = `${date.getMinutes()}`.padStart(2, '0');
  const ss = `${date.getSeconds()}`.padStart(2, '0');
  return `${y}${m}${d}${hh}${mm}${ss}`;
}

function relativeFromRoot(filePath) {
  return path.relative(ROOT, filePath).replace(/\\/g, '/');
}

function quoteArg(value) {
  if (!/\s|"/.test(value)) {
    return value;
  }
  return `"${value.replace(/"/g, '\\"')}"`;
}

function commandPreview(command, args) {
  return [command, ...args].map((item) => quoteArg(String(item))).join(' ');
}

function terminateProcessTree(pid, label) {
  if (!pid) {
    return;
  }
  if (process.platform === 'win32') {
    spawnSync('taskkill', ['/pid', String(pid), '/t', '/f'], {
      stdio: 'ignore',
      windowsHide: true,
    });
    return;
  }
  try {
    process.kill(pid, 'SIGTERM');
  } catch (error) {
    console.warn(`[${label}] failed to stop process ${pid}: ${error.message}`);
  }
}

function isBlockedError(message) {
  const normalized = String(message || '').trim().toLowerCase();
  if (!normalized) {
    return false;
  }
  return normalized.includes('timeout')
    || normalized.includes('timed out')
    || normalized.includes('401')
    || normalized.includes('403')
    || normalized.includes('unauthorized')
    || normalized.includes('api key')
    || normalized.includes('connection refused')
    || normalized.includes('connectexception')
    || normalized.includes('connect timed out')
    || normalized.includes('read timed out')
    || normalized.includes('certificate')
    || normalized.includes('ssl')
    || normalized.includes('econnrefused')
    || normalized.includes('socket')
    || normalized.includes('network')
    || normalized.includes('unable to connect')
    || normalized.includes('host is down');
}

function tryListenPort(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', (error) => {
      if (error && error.code === 'EADDRINUSE') {
        resolve(false);
        return;
      }
      resolve(false);
    });
    server.once('listening', () => {
      server.close(() => resolve(true));
    });
    server.listen(port, '127.0.0.1');
  });
}

async function assertPortAvailable(port) {
  const available = await tryListenPort(port);
  if (!available) {
    throw new Error(`Port ${port} is already in use. Please release it and rerun the acceptance script.`);
  }
}

module.exports = {
  ROOT,
  DESKTOP_DIR,
  SERVER_DIR,
  OUTPUT_ROOT,
  BACKEND_URL,
  PREVIEW_URL,
  BACKEND_HEALTH_URL,
  parseArgs,
  sleep,
  waitFor,
  waitForUrl,
  apiRequest,
  ensureDir,
  resetDir,
  writeJson,
  writeText,
  readJsonIfExists,
  nowTimestamp,
  relativeFromRoot,
  commandPreview,
  terminateProcessTree,
  isBlockedError,
  assertPortAvailable,
};

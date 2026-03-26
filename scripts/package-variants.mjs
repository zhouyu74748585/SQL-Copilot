import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const ROOT_DIR = path.resolve(__dirname, '..');
const SERVER_DIR = path.join(ROOT_DIR, 'apps', 'server');
const SERVER_TARGET_DIR = path.join(SERVER_DIR, 'target');
const DESKTOP_DIR = path.join(ROOT_DIR, 'apps', 'desktop');
const DESKTOP_BACKEND_STAGE_DIR = path.join(DESKTOP_DIR, 'resources', 'backend');
const DESKTOP_QDRANT_STAGE_DIR = path.join(DESKTOP_DIR, 'resources', 'qdrant-package', 'qdrant');
const RELEASE_DIR = path.join(ROOT_DIR, 'release');
const BACKEND_BUILD_STAGE_ROOT = path.join(RELEASE_DIR, '.backend-build-stage');
const TEMP_DIR = path.join(RELEASE_DIR, '.jlink-temp');
const DESKTOP_RELEASE_ROOT = (() => {
  const customOutputDir = (process.env.SQLCOPILOT_DESKTOP_RELEASE_DIR || '').trim();
  return customOutputDir ? path.resolve(ROOT_DIR, customOutputDir) : path.join(RELEASE_DIR, 'desktop');
})();
const BACKEND_RELEASE_ROOT = path.join(RELEASE_DIR, 'backend');
const TOOLCHAIN_CACHE_DIR = path.join(RELEASE_DIR, '.toolchains');
const DESKTOP_PACKAGER_STAGE_ROOT = path.join(RELEASE_DIR, '.desktop-package-stage');
const ELECTRON_PACKAGER_CLI = path.join(ROOT_DIR, 'node_modules', '@electron', 'packager', 'bin', 'electron-packager.js');
const ELECTRON_CACHE_DIR = (process.env.ELECTRON_CACHE || '').trim()
  || (process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA || path.join(process.env.USERPROFILE || ROOT_DIR, 'AppData', 'Local'), 'electron', 'Cache')
    : path.join(process.env.HOME || ROOT_DIR, '.cache', 'electron'));
const EXTRA_MODULES_ENV = 'SQLCOPILOT_JLINK_EXTRA_MODULES';
const RETRY_DELETE_COUNT = 6;
const RETRY_DELETE_DELAY_MS = 1000;
const MANDATORY_JLINK_MODULES = [
  'java.base',
  'java.compiler',
  'java.desktop',
  'java.instrument',
  'java.logging',
  'java.management',
  'java.management.rmi',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.security.sasl',
  'java.sql',
  'java.transaction.xa',
  'java.xml',
  'java.xml.crypto',
  'jdk.charsets',
  'jdk.crypto.ec',
  'jdk.localedata',
  'jdk.management',
  'jdk.naming.dns',
  'jdk.naming.rmi',
  'jdk.unsupported',
  'jdk.zipfs',
];

const INCLUDE_DESKTOP = resolveSwitch('SQLCOPILOT_INCLUDE_DESKTOP', '1');
const EXPORT_BACKEND = resolveSwitch('SQLCOPILOT_EXPORT_BACKEND', '0');
const ELECTRON_DIST = (process.env.SQLCOPILOT_ELECTRON_DIST || '').trim();
const SHOULD_DISABLE_MAC_SIGN = (process.env.SQLCOPILOT_MAC_SIGN || '0').trim() !== '1';
const QDRANT_VERSION = (process.env.QDRANT_VERSION || 'v1.13.4').trim();
const DESKTOP_BUILD_HEAP_SIZE_MB = '8192';
const DESKTOP_MANIFEST = JSON.parse(fs.readFileSync(path.join(DESKTOP_DIR, 'package.json'), 'utf8'));
const PRODUCT_NAME = DESKTOP_MANIFEST.build?.productName || 'SQL Copliot';
const PRODUCT_NAME_SLUG = PRODUCT_NAME.replace(/\s+/g, '-');
const APP_ID = DESKTOP_MANIFEST.build?.appId || 'com.sqlcopilot.desktop';
const ELECTRON_VERSION = String(DESKTOP_MANIFEST.devDependencies?.electron || '36.2.1').replace(/^[^\d]*/, '');

const TARGETS = Object.freeze({
  'win-x64': {
    id: 'win-x64',
    electronPlatform: 'win',
    electronArch: 'x64',
    qdrantPlatformKey: 'win32-x64',
    runtimeOs: 'windows',
    runtimeArch: 'x64',
    hostAliases: ['win32-x64', 'windows-x64', 'win-x86', 'windows-x86'],
    builderArgs: ['--win', 'nsis', '--x64'],
  },
  'mac-arm64': {
    id: 'mac-arm64',
    electronPlatform: 'mac',
    electronArch: 'arm64',
    qdrantPlatformKey: 'darwin-arm64',
    runtimeOs: 'mac',
    runtimeArch: 'aarch64',
    hostAliases: ['mac-arm', 'darwin-arm64', 'darwin-arm', 'mac-aarch64'],
    builderArgs: ['--mac', 'zip', '--arm64', '--config.mac.target=zip'],
  },
  'mac-x64': {
    id: 'mac-x64',
    electronPlatform: 'mac',
    electronArch: 'x64',
    qdrantPlatformKey: 'darwin-x64',
    runtimeOs: 'mac',
    runtimeArch: 'x64',
    hostAliases: ['mac-x86', 'macos-x64', 'macos-x86', 'darwin-x64', 'darwin-x86'],
    builderArgs: ['--mac', 'zip', '--x64', '--config.mac.target=zip'],
  },
  'linux-x64': {
    id: 'linux-x64',
    electronPlatform: 'linux',
    electronArch: 'x64',
    qdrantPlatformKey: 'linux-x64',
    runtimeOs: 'linux',
    runtimeArch: 'x64',
    hostAliases: ['linux-x86', 'linux-amd64'],
    builderArgs: ['--linux', 'AppImage', '--x64'],
  },
});

function resolveSwitch(name, fallback) {
  const raw = (process.env[name] || fallback).trim();
  return raw === '1';
}

function resolveShellCommand(command) {
  if (process.platform !== 'win32') {
    return command;
  }
  if (['npm', 'npx', 'mvn'].includes(command)) {
    return `${command}.cmd`;
  }
  if (['curl', 'tar'].includes(command)) {
    return `${command}.exe`;
  }
  return `${command}.exe`;
}

function runCommand(command, args, options = {}) {
  const result = executeCommand(command, args, options);
  if (result.error) {
    const commandLine = [command, ...args].join(' ');
    throw new Error(`${commandLine}\n${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(formatCommandFailure(command, args, result, options.captureOutput === true));
  }
  return result;
}

function executeCommand(command, args, options = {}) {
  const {
    cwd = ROOT_DIR,
    env,
    captureOutput = false,
  } = options;
  const useShell = process.platform === 'win32' && command.toLowerCase().endsWith('.cmd');
  return spawnSync(command, args, {
    cwd,
    env: env ? { ...process.env, ...env } : process.env,
    encoding: 'utf8',
    stdio: captureOutput ? 'pipe' : 'inherit',
    shell: useShell,
  });
}

function formatCommandFailure(command, args, result, captured) {
  const commandLine = [command, ...args].join(' ');
  const exitDetail = result.signal
    ? `Signal ${result.signal}`
    : `Exit code ${result.status ?? 'unknown'}`;
  if (!captured) {
    return `${commandLine}\n${exitDetail}`;
  }
  const stderr = (result.stderr || '').trim();
  const stdout = (result.stdout || '').trim();
  const detail = [stdout, stderr].filter(Boolean).join('\n');
  return detail ? `${commandLine}\n${detail}` : `${commandLine}\n${exitDetail}`;
}

function relayCapturedOutput(result) {
  if (result.stdout) {
    process.stdout.write(result.stdout);
  }
  if (result.stderr) {
    process.stderr.write(result.stderr);
  }
}

function hasWindowsRceditCommitIssue(logText) {
  return process.platform === 'win32'
    && logText.includes('rcedit-x64.exe')
    && logText.includes('Unable to commit changes');
}

function runElectronBuilderCommand(targetName, args, options, desktopOutputDir) {
  const maxAttempts = process.platform === 'win32' ? 2 : 1;
  let lastFailure = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const result = executeCommand(resolveShellCommand('npx'), args, {
      ...options,
      captureOutput: true,
    });
    relayCapturedOutput(result);

    const combinedOutput = `${result.stdout || ''}\n${result.stderr || ''}`;
    const commitIssueDetected = hasWindowsRceditCommitIssue(combinedOutput);

    if (result.error || result.status !== 0) {
      const failureMessage = result.error
        ? `${[resolveShellCommand('npx'), ...args].join(' ')}\n${result.error.message}`
        : formatCommandFailure(resolveShellCommand('npx'), args, result, true);
      lastFailure = new Error(failureMessage);
      if (commitIssueDetected && attempt < maxAttempts) {
        console.warn(`[windows-rcedit] Detected commit issue for ${targetName}, rebuilding executable from a clean output directory (attempt ${attempt + 1}/${maxAttempts})`);
        ensureCleanDir(desktopOutputDir);
        continue;
      }
      throw lastFailure;
    }

    if (commitIssueDetected) {
      if (attempt < maxAttempts) {
        console.warn(`[windows-rcedit] Detected commit issue for ${targetName}, rebuilding executable from a clean output directory (attempt ${attempt + 1}/${maxAttempts})`);
        ensureCleanDir(desktopOutputDir);
        continue;
      }
      console.warn(`[windows-rcedit] Commit issue still detected for ${targetName} after ${maxAttempts} attempts; keeping the last successful artifact for manual verification.`);
    }

    return result;
  }

  if (lastFailure) {
    throw lastFailure;
  }

  throw new Error(`electron-builder failed for ${targetName}`);
}

function ensureCleanDir(targetDir) {
  removePathWithRetry(targetDir);
  fs.mkdirSync(targetDir, { recursive: true });
}

function ensureEmptyDir(targetDir) {
  fs.mkdirSync(targetDir, { recursive: true });
  for (const entry of fs.readdirSync(targetDir)) {
    removePathWithRetry(path.join(targetDir, entry));
  }
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function escapePowerShellSingleQuoted(value) {
  return value.replace(/'/g, "''");
}

function releaseWindowsDirLocks(targetDir) {
  if (process.platform !== 'win32' || !fs.existsSync(targetDir)) {
    return false;
  }

  const normalizedPath = path.resolve(targetDir);
  const stat = fs.statSync(normalizedPath);
  const lockRoot = stat.isDirectory() ? normalizedPath : path.dirname(normalizedPath);
  const psDir = escapePowerShellSingleQuoted(lockRoot);
  const script = `
$targetDir = '${psDir}'
$killed = @()
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
  Where-Object {
    $_.ExecutablePath -and $_.ExecutablePath.StartsWith($targetDir, [System.StringComparison]::OrdinalIgnoreCase)
  } |
  ForEach-Object {
    try {
      Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
      $killed += $_.ExecutablePath
    } catch {
    }
  }
if ($killed.Count -gt 0) {
  $killed | Sort-Object -Unique | ForEach-Object { Write-Output $_ }
}
`.trim();

  const result = spawnSync('powershell.exe', [
    '-NoLogo',
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    script,
  ], {
    cwd: ROOT_DIR,
    encoding: 'utf8',
    stdio: 'pipe',
  });

  if (result.error || result.status !== 0) {
    return false;
  }

  const killedItems = (result.stdout || '')
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
  if (killedItems.length > 0) {
    console.warn(`[windows-lock] Closed packaged app processes under ${lockRoot}`);
  }
  return killedItems.length > 0;
}

function removePathWithRetry(targetPath) {
  let lastError = null;
  for (let attempt = 1; attempt <= RETRY_DELETE_COUNT; attempt += 1) {
    try {
      fs.rmSync(targetPath, { recursive: true, force: true });
      return;
    } catch (error) {
      lastError = error;
      if (process.platform === 'win32' && ['EBUSY', 'EPERM', 'ENOTEMPTY'].includes(error.code)) {
        const released = releaseWindowsDirLocks(targetPath);
        if (attempt < RETRY_DELETE_COUNT) {
          if (released || attempt > 1) {
            console.warn(`[windows-lock] Retry ${attempt}/${RETRY_DELETE_COUNT} removing ${targetPath}`);
          }
          sleep(RETRY_DELETE_DELAY_MS);
          continue;
        }
      }
      throw error;
    }
  }

  if (lastError) {
    throw lastError;
  }
}

function cleanupStageDir() {
  ensureEmptyDir(DESKTOP_BACKEND_STAGE_DIR);
  fs.writeFileSync(path.join(DESKTOP_BACKEND_STAGE_DIR, '.gitkeep'), '', 'utf8');
  removePathWithRetry(path.dirname(DESKTOP_QDRANT_STAGE_DIR));
  removePathWithRetry(BACKEND_BUILD_STAGE_ROOT);
}

function copyIfExists(sourcePath, targetPath) {
  if (fs.existsSync(sourcePath)) {
    fs.copyFileSync(sourcePath, targetPath);
  }
}

function withNodeHeapSize(env = {}) {
  const heapOption = `--max-old-space-size=${DESKTOP_BUILD_HEAP_SIZE_MB}`;
  const merged = (env.NODE_OPTIONS || process.env.NODE_OPTIONS || '').trim();
  if (!merged) {
    return {
      ...env,
      NODE_OPTIONS: heapOption,
    };
  }
  if (merged.includes(heapOption)) {
    return {
      ...env,
      NODE_OPTIONS: merged,
    };
  }
  return {
    ...env,
    NODE_OPTIONS: `${merged} ${heapOption}`.trim(),
  };
}

function copyRuntimeHome(sourceDir, targetDir) {
  fs.mkdirSync(targetDir, { recursive: true });
  fs.cpSync(sourceDir, targetDir, { recursive: true });
}

let cachedJavaHome = '';

function resolveJavaHome() {
  if (cachedJavaHome) {
    return cachedJavaHome;
  }
  const envJavaHome = (process.env.JAVA_HOME || '').trim();
  if (envJavaHome) {
    cachedJavaHome = envJavaHome;
    return cachedJavaHome;
  }

  const result = spawnSync('java', ['-XshowSettings:properties', '-version'], {
    cwd: ROOT_DIR,
    encoding: 'utf8',
    stdio: 'pipe',
  });
  if (result.status !== 0) {
    throw new Error('Failed to resolve JAVA_HOME from `java`');
  }

  const combined = `${result.stdout || ''}\n${result.stderr || ''}`;
  const match = combined.match(/^\s*java\.home = (.+)$/m);
  if (!match) {
    throw new Error('Unable to resolve java.home from `java -XshowSettings:properties -version`');
  }

  cachedJavaHome = match[1].trim();
  return cachedJavaHome;
}

function resolveJavaTool(toolName) {
  const fileName = process.platform === 'win32' ? `${toolName}.exe` : toolName;
  const javaHome = resolveJavaHome();
  const toolPath = path.join(javaHome, 'bin', fileName);
  if (!fs.existsSync(toolPath)) {
    throw new Error(`JDK tool not found: ${toolPath}. Please use a full JDK 17 installation.`);
  }
  return toolPath;
}

function resolveExtraJlinkModules() {
  return (process.env[EXTRA_MODULES_ENV] || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}

function locatePackagedJar(targetDir = SERVER_TARGET_DIR) {
  const jars = fs.existsSync(targetDir)
    ? fs.readdirSync(targetDir)
        .filter((name) => name.endsWith('.jar') && !name.startsWith('original-'))
        .sort()
    : [];
  if (!jars.length) {
    throw new Error(`No packaged jar found in ${targetDir}`);
  }
  return path.join(targetDir, jars[0]);
}

function detectJlinkModules(unpackedDir) {
  const bootClassesDir = path.join(unpackedDir, 'BOOT-INF', 'classes');
  const bootLibDir = path.join(unpackedDir, 'BOOT-INF', 'lib');
  const libJars = fs.existsSync(bootLibDir)
    ? fs.readdirSync(bootLibDir)
        .filter((name) => name.endsWith('.jar'))
        .map((name) => path.join(bootLibDir, name))
    : [];

  const modules = new Set(MANDATORY_JLINK_MODULES);
  if (process.platform === 'win32') {
    modules.add('jdk.crypto.mscapi');
  }
  for (const item of resolveExtraJlinkModules()) {
    modules.add(item);
  }

  if (!fs.existsSync(bootClassesDir)) {
    return modules;
  }

  const jdepsCommand = resolveJavaTool('jdeps');
  const args = [
    '--multi-release',
    '17',
    '--ignore-missing-deps',
    '--recursive',
    '--print-module-deps',
  ];
  if (libJars.length > 0) {
    args.push('--class-path', libJars.join(path.delimiter));
  }
  args.push(bootClassesDir);

  try {
    const result = runCommand(jdepsCommand, args, { captureOutput: true });
    const detected = (result.stdout || '')
      .trim()
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
    for (const item of detected) {
      modules.add(item);
    }
  } catch (error) {
    console.warn(`[jlink] jdeps detection failed, fallback to predefined modules.\n${error.message}`);
  }

  return modules;
}

function createJlinkRuntime(jarPath) {
  const jarCommand = resolveJavaTool('jar');
  const jlinkCommand = resolveJavaTool('jlink');
  const backendTempDir = path.join(TEMP_DIR, 'backend');
  const unpackedDir = path.join(backendTempDir, 'unpacked');
  const runtimeDir = path.join(backendTempDir, 'runtime');

  ensureCleanDir(unpackedDir);
  fs.mkdirSync(TEMP_DIR, { recursive: true });
  runCommand(jarCommand, ['xf', jarPath], { cwd: unpackedDir });

  const modules = [...detectJlinkModules(unpackedDir)].sort();
  const moduleList = modules.join(',');
  console.log('==> [backend] jlink runtime');
  console.log(`    modules: ${moduleList}`);

  fs.rmSync(runtimeDir, { recursive: true, force: true });
  runCommand(jlinkCommand, [
    '--add-modules',
    moduleList,
    '--output',
    runtimeDir,
    '--strip-debug',
    '--no-header-files',
    '--no-man-pages',
    '--compress=2',
  ]);

  return runtimeDir;
}

function writeBackendLaunchScripts(targetDir) {
  const runSh = `#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILE="\${1:-\${SQLCOPILOT_BACKEND_PROFILE:-}}"
JAVA_BIN="\${JAVA_BIN:-$BASE_DIR/jre/bin/java}"
DATA_ROOT="\${SQLCOPILOT_DATA_DIR:-\${XDG_DATA_HOME:-$HOME/.local/share}/sql-copilot}"

if [[ ! -x "$JAVA_BIN" ]]; then
  echo "Bundled Java runtime not found: $JAVA_BIN" >&2
  exit 1
fi

JAR_FILE="$(find "$BASE_DIR" -maxdepth 1 -name "*.jar" | head -n 1)"
if [[ -z "$JAR_FILE" ]]; then
  echo "No backend jar found in $BASE_DIR" >&2
  exit 1
fi

mkdir -p "$DATA_ROOT"
export SQLCOPILOT_DATA_DIR="$DATA_ROOT"

ARGS=(-Dfile.encoding=UTF-8 -jar "$JAR_FILE")
if [[ -n "$PROFILE" ]]; then
  ARGS+=(--spring.profiles.active="$PROFILE")
fi

exec "$JAVA_BIN" "\${ARGS[@]}"
`;

  const runCmd = `@echo off
setlocal enabledelayedexpansion
set "BASE_DIR=%~dp0"
set "PROFILE=%~1"
if "%PROFILE%"=="" if defined SQLCOPILOT_BACKEND_PROFILE set "PROFILE=%SQLCOPILOT_BACKEND_PROFILE%"
set "JAVA_BIN=%BASE_DIR%jre\\bin\\java.exe"
if defined SQLCOPILOT_JAVA_BIN set "JAVA_BIN=%SQLCOPILOT_JAVA_BIN%"
if not defined SQLCOPILOT_DATA_DIR set "SQLCOPILOT_DATA_DIR=%LOCALAPPDATA%\\SQL Copliot"

if not exist "%JAVA_BIN%" (
  echo Bundled Java runtime not found: %JAVA_BIN%
  exit /b 1
)

if not exist "%SQLCOPILOT_DATA_DIR%" mkdir "%SQLCOPILOT_DATA_DIR%"

for %%f in ("%BASE_DIR%*.jar") do (
  if defined PROFILE (
    "%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%%f" --spring.profiles.active=%PROFILE%
  ) else (
    "%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%%f"
  )
  exit /b !ERRORLEVEL!
)

echo No backend jar found in %BASE_DIR%
exit /b 1
`;

  fs.writeFileSync(path.join(targetDir, 'run.sh'), runSh, 'utf8');
  fs.writeFileSync(path.join(targetDir, 'run.cmd'), runCmd, 'utf8');
}

function prepareBackendRuntime(targetDir, jarPath, runtimeHome) {
  ensureEmptyDir(targetDir);
  fs.copyFileSync(jarPath, path.join(targetDir, path.basename(jarPath)));
  copyRuntimeHome(runtimeHome, path.join(targetDir, 'jre'));
  copyIfExists(path.join(SERVER_DIR, 'src', 'main', 'resources', 'application.yml'), path.join(targetDir, 'application.yml'));
  writeBackendLaunchScripts(targetDir);
}

function resolveBackendOnnxRuntimeArtifactId(target) {
  return target.runtimeOs === 'mac' ? 'onnxruntime' : 'onnxruntime_gpu';
}

function stageBackendProject(target) {
  const stageDir = path.join(BACKEND_BUILD_STAGE_ROOT, target.id);
  ensureCleanDir(stageDir);
  fs.cpSync(SERVER_DIR, stageDir, {
    recursive: true,
    filter: (sourcePath) => {
      const relativePath = path.relative(SERVER_DIR, sourcePath);
      if (!relativePath) {
        return true;
      }
      return !relativePath.split(path.sep).includes('target');
    },
  });
  return stageDir;
}

function buildBackend(target) {
  const onnxRuntimeArtifactId = resolveBackendOnnxRuntimeArtifactId(target);
  const backendProjectDir = stageBackendProject(target);
  console.log(`==> [backend:${target.id}] package (${onnxRuntimeArtifactId})`);
  runCommand(resolveShellCommand('mvn'), [
    '-f',
    path.join(backendProjectDir, 'pom.xml'),
    'clean',
    'package',
    '-DskipTests',
    `-Donnxruntime.artifactId=${onnxRuntimeArtifactId}`,
  ]);
  return locatePackagedJar(path.join(backendProjectDir, 'target'));
}

function buildDesktopTypeCheckOnce() {
  if (!INCLUDE_DESKTOP) {
    return;
  }
  console.log('==> [desktop] type-check');
  runCommand(resolveShellCommand('npm'), ['run', '-w', '@sqlcopilot/desktop', 'type-check']);
}

function buildDesktopAssetsOnce() {
  if (!INCLUDE_DESKTOP) {
    return;
  }
  console.log('==> [desktop] build');
  const buildArgs = ['run', '-w', '@sqlcopilot/desktop', 'build'];
  const baseEnv = withNodeHeapSize();
  try {
    runCommand(resolveShellCommand('npm'), buildArgs, { env: baseEnv });
  } catch (error) {
    if (process.platform !== 'darwin') {
      throw error;
    }
    console.warn('==> [desktop] macOS build retry with safe build profile');
    runCommand(resolveShellCommand('npm'), buildArgs, {
      env: withNodeHeapSize({
        SQLCOPILOT_VITE_SAFE_BUILD: '1',
      }),
    });
  }
}

function currentHostTarget() {
  if (process.platform === 'win32') {
    return process.arch === 'arm64' ? 'win-arm64' : 'win-x64';
  }
  if (process.platform === 'darwin') {
    return process.arch === 'arm64' ? 'mac-arm64' : 'mac-x64';
  }
  return process.arch === 'arm64' ? 'linux-arm64' : 'linux-x64';
}

function normalizeTargetToken(value) {
  return value
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '-')
    .replace(/_/g, '-');
}

function resolveTargetId(token) {
  const normalized = normalizeTargetToken(token);
  if (TARGETS[normalized]) {
    return normalized;
  }
  for (const target of Object.values(TARGETS)) {
    if (target.hostAliases.includes(normalized)) {
      return target.id;
    }
  }
  throw new Error(`Unsupported target: ${token}. Supported targets: ${Object.keys(TARGETS).join(', ')}`);
}

function parseTargets(argv) {
  const tokens = [];
  for (const arg of argv) {
    if (arg.startsWith('--targets=')) {
      tokens.push(...arg.slice('--targets='.length).split(','));
      continue;
    }
    if (arg === '--all') {
      tokens.push('win-x64', 'mac-arm64', 'mac-x64', 'linux-x64');
      continue;
    }
    if (arg.startsWith('--')) {
      throw new Error(`Unsupported option: ${arg}`);
    }
    tokens.push(...arg.split(','));
  }

  if (tokens.length === 0) {
    const hostTarget = currentHostTarget();
    if (!TARGETS[hostTarget]) {
      throw new Error(`Current host target is not supported by this script: ${hostTarget}`);
    }
    return [TARGETS[hostTarget]];
  }

  const seen = new Set();
  const result = [];
  for (const token of tokens.map((item) => item.trim()).filter(Boolean)) {
    const targetId = resolveTargetId(token);
    if (!seen.has(targetId)) {
      seen.add(targetId);
      result.push(TARGETS[targetId]);
    }
  }
  return result;
}

function escapePowerShellDoubleQuoted(value) {
  return value.replace(/`/g, '``').replace(/"/g, '`"');
}

function extractArchive(archivePath, destinationDir) {
  ensureCleanDir(destinationDir);
  if (archivePath.endsWith('.zip')) {
    const archive = escapePowerShellDoubleQuoted(path.resolve(archivePath));
    const destination = escapePowerShellDoubleQuoted(path.resolve(destinationDir));
    runCommand('powershell.exe', [
      '-NoLogo',
      '-NoProfile',
      '-NonInteractive',
      '-Command',
      `Expand-Archive -LiteralPath "${archive}" -DestinationPath "${destination}" -Force`,
    ]);
    return;
  }

  runCommand(resolveShellCommand('tar'), ['-xzf', archivePath, '-C', destinationDir]);
}

function findJavaHome(rootDir) {
  const candidates = [];

  function walk(dir, depth) {
    if (depth > 4 || !fs.existsSync(dir)) {
      return;
    }
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    const javaExecutableNames = ['java', 'java.exe'];
    if (javaExecutableNames.some((fileName) => fs.existsSync(path.join(dir, 'bin', fileName)))) {
      candidates.push(dir);
    }
    for (const entry of entries) {
      if (entry.isDirectory()) {
        walk(path.join(dir, entry.name), depth + 1);
      }
    }
  }

  walk(rootDir, 0);
  if (candidates.length === 0) {
    throw new Error(`Unable to locate Java home in ${rootDir}`);
  }
  return candidates.sort((left, right) => left.length - right.length)[0];
}

function fetchJson(url) {
  const result = runCommand(resolveShellCommand('curl'), ['-fsSL', url], { captureOutput: true });
  return JSON.parse(result.stdout);
}

function downloadFile(url, targetFile) {
  fs.mkdirSync(path.dirname(targetFile), { recursive: true });
  const tempFile = `${targetFile}.downloading`;
  fs.rmSync(tempFile, { force: true });
  runCommand(resolveShellCommand('curl'), ['-fL', '--retry', '5', '--retry-delay', '2', url, '-o', tempFile]);
  fs.rmSync(targetFile, { force: true });
  fs.renameSync(tempFile, targetFile);
}

function resolveRuntimeArchive(target) {
  const cacheDir = path.join(TOOLCHAIN_CACHE_DIR, 'archives');
  fs.mkdirSync(cacheDir, { recursive: true });
  const apiUrl = `https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=${target.runtimeArch}&image_type=jdk&os=${target.runtimeOs}&vendor=eclipse`;
  const payload = fetchJson(apiUrl);
  const binary = Array.isArray(payload) ? payload[0]?.binary : null;
  const pkg = binary?.package;
  if (!pkg?.link || !pkg?.name) {
    throw new Error(`Unable to resolve JDK package for target ${target.id}`);
  }
  const archivePath = path.join(cacheDir, pkg.name);
  if (!fs.existsSync(archivePath)) {
    console.log(`==> [runtime:${target.id}] download ${pkg.name}`);
    downloadFile(pkg.link, archivePath);
  } else {
    console.log(`==> [runtime:${target.id}] reuse ${pkg.name}`);
  }
  return archivePath;
}

function resolveBundledRuntimeHome(target, jarPath) {
  if (target.id === currentHostTarget()) {
    return createJlinkRuntime(jarPath);
  }

  const extractDir = path.join(TOOLCHAIN_CACHE_DIR, 'expanded', target.id);
  if (fs.existsSync(extractDir) && fs.readdirSync(extractDir).length > 0) {
    console.log(`==> [runtime:${target.id}] reuse extracted home`);
    return findJavaHome(extractDir);
  }

  let archivePath = resolveRuntimeArchive(target);
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    try {
      console.log(`==> [runtime:${target.id}] extract`);
      extractArchive(archivePath, extractDir);
      return findJavaHome(extractDir);
    } catch (error) {
      if (attempt === 2) {
        throw error;
      }
      console.warn(`[runtime:${target.id}] extract failed, redownloading toolchain archive and retrying once`);
      fs.rmSync(archivePath, { force: true });
      fs.rmSync(extractDir, { recursive: true, force: true });
      archivePath = resolveRuntimeArchive(target);
    }
  }

  throw new Error(`Unable to prepare runtime for target ${target.id}`);
}

function findBinary(rootDir, fileName) {
  const entries = fs.readdirSync(rootDir, { withFileTypes: true });
  for (const entry of entries) {
    const absolutePath = path.join(rootDir, entry.name);
    if (entry.isFile() && entry.name === fileName) {
      return absolutePath;
    }
    if (entry.isDirectory()) {
      const nested = findBinary(absolutePath, fileName);
      if (nested) {
        return nested;
      }
    }
  }
  return null;
}

function ensureQdrantBinary(target) {
  const platformKey = target.qdrantPlatformKey;
  const binaryName = platformKey.startsWith('win32-') ? 'qdrant.exe' : 'qdrant';
  const targetDir = path.join(DESKTOP_DIR, 'resources', 'qdrant', platformKey);
  const targetBinary = path.join(targetDir, binaryName);
  if (fs.existsSync(targetBinary)) {
    return targetBinary;
  }

  console.log(`==> [qdrant:${target.id}] download ${QDRANT_VERSION}`);
  const tempDir = path.join(RELEASE_DIR, '.qdrant-download', platformKey);
  ensureCleanDir(tempDir);
  const releaseApi = `https://api.github.com/repos/qdrant/qdrant/releases/tags/${QDRANT_VERSION}`;
  const release = fetchJson(releaseApi);
  const assets = release.assets || [];
  const assetKeywords = {
    'darwin-arm64': ['aarch64', 'apple', 'darwin'],
    'darwin-x64': ['x86_64', 'apple', 'darwin'],
    'linux-arm64': ['aarch64', 'linux'],
    'linux-x64': ['x86_64', 'linux'],
    'win32-arm64': ['aarch64', 'windows'],
    'win32-x64': ['x86_64', 'windows'],
  };
  const keywords = assetKeywords[platformKey] || [];
  const asset = assets.find((item) => {
    const lower = String(item.name || '').toLowerCase();
    return keywords.every((keyword) => lower.includes(keyword))
      && (lower.endsWith('.tar.gz') || lower.endsWith('.zip'));
  });
  if (!asset?.browser_download_url || !asset?.name) {
    throw new Error(`No matching qdrant asset found for ${platformKey} in release ${QDRANT_VERSION}`);
  }

  const archivePath = path.join(tempDir, asset.name);
  const extractDir = path.join(tempDir, 'extracted');
  downloadFile(asset.browser_download_url, archivePath);
  extractArchive(archivePath, extractDir);

  const downloadedBinary = findBinary(extractDir, binaryName);
  if (!downloadedBinary) {
    throw new Error(`Unable to find ${binaryName} in downloaded archive for ${platformKey}`);
  }

  fs.mkdirSync(targetDir, { recursive: true });
  fs.copyFileSync(downloadedBinary, targetBinary);
  if (!platformKey.startsWith('win32-')) {
    fs.chmodSync(targetBinary, 0o755);
  }

  removePathWithRetry(tempDir);
  return targetBinary;
}

function preparePackagedQdrant(target) {
  runCommand(resolveShellCommand('node'), [
    path.join('apps', 'desktop', 'scripts', 'prepare-qdrant-package.mjs'),
    `--target=${target.qdrantPlatformKey}`,
  ], { cwd: ROOT_DIR });
}

function prepareDesktopOutputDir(target) {
  const outputDir = path.join(DESKTOP_RELEASE_ROOT, target.id);
  ensureCleanDir(outputDir);
  return outputDir;
}

function ensureArtifactsExist(target, outputDir) {
  const entries = fs.readdirSync(outputDir, { withFileTypes: true });
  if (entries.length === 0) {
    throw new Error(`No artifacts were produced for ${target.id} in ${outputDir}`);
  }
}

function writePackagerManifest(targetDir) {
  const manifest = {
    name: DESKTOP_MANIFEST.name,
    version: DESKTOP_MANIFEST.version,
    description: DESKTOP_MANIFEST.description,
    author: DESKTOP_MANIFEST.author,
    main: DESKTOP_MANIFEST.main,
    type: DESKTOP_MANIFEST.type,
  };
  fs.writeFileSync(path.join(targetDir, 'package.json'), JSON.stringify(manifest, null, 2), 'utf8');
}

function stagePackagerApp(target) {
  const stageDir = path.join(DESKTOP_PACKAGER_STAGE_ROOT, target.id);
  ensureCleanDir(stageDir);
  fs.cpSync(path.join(DESKTOP_DIR, 'dist'), path.join(stageDir, 'dist'), { recursive: true });
  fs.cpSync(path.join(DESKTOP_DIR, 'electron'), path.join(stageDir, 'electron'), { recursive: true });
  writePackagerManifest(stageDir);
  return stageDir;
}

function ensureElectronZip(targetPlatform, targetArch) {
  fs.mkdirSync(ELECTRON_CACHE_DIR, { recursive: true });
  const fileName = `electron-v${ELECTRON_VERSION}-${targetPlatform}-${targetArch}.zip`;
  const targetFile = path.join(ELECTRON_CACHE_DIR, fileName);
  if (fs.existsSync(targetFile)) {
    return targetFile;
  }
  const url = `https://github.com/electron/electron/releases/download/v${ELECTRON_VERSION}/${fileName}`;
  console.log(`==> [electron:${targetPlatform}-${targetArch}] download ${fileName}`);
  downloadFile(url, targetFile);
  return targetFile;
}

function runElectronPackager(args) {
  runCommand(resolveShellCommand('node'), [ELECTRON_PACKAGER_CLI, ...args], {
    cwd: ROOT_DIR,
  });
}

function findPackagedEntry(outputDir, suffix) {
  return fs.readdirSync(outputDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .find((entry) => entry.endsWith(suffix));
}

function createZipArtifact(outputDir, target) {
  const packagedEntry = findPackagedEntry(outputDir, `darwin-${target.electronArch}`);
  if (!packagedEntry) {
    throw new Error(`Unable to locate packaged mac app directory in ${outputDir}`);
  }
  const zipPath = path.join(outputDir, `${PRODUCT_NAME_SLUG}-${DESKTOP_MANIFEST.version}-mac-${target.electronArch}.zip`);
  if (fs.existsSync(zipPath)) {
    fs.rmSync(zipPath, { force: true });
  }
  runCommand(resolveShellCommand('tar'), ['-a', '-cf', zipPath, `./${packagedEntry}`], { cwd: outputDir });
}

function createMacTarGzArtifact(outputDir, target) {
  const packagedEntry = findPackagedEntry(outputDir, `darwin-${target.electronArch}`);
  if (!packagedEntry) {
    throw new Error(`Unable to locate packaged mac app directory in ${outputDir}`);
  }
  const archivePath = path.join(outputDir, `${PRODUCT_NAME_SLUG}-${DESKTOP_MANIFEST.version}-mac-${target.electronArch}.tar.gz`);
  runCommand(resolveShellCommand('python'), [
    path.join('scripts', 'create-macos-archive.py'),
    path.join(outputDir, packagedEntry),
    archivePath,
  ], { cwd: ROOT_DIR });
}

function createTarGzArtifact(outputDir, target) {
  const packagedEntry = findPackagedEntry(outputDir, `linux-${target.electronArch}`);
  if (!packagedEntry) {
    throw new Error(`Unable to locate packaged linux app directory in ${outputDir}`);
  }
  const archivePath = path.join(outputDir, `${PRODUCT_NAME_SLUG}-${DESKTOP_MANIFEST.version}-linux-${target.electronArch}.tar.gz`);
  if (fs.existsSync(archivePath)) {
    fs.rmSync(archivePath, { force: true });
  }
  runCommand(resolveShellCommand('tar'), ['-czf', archivePath, `./${packagedEntry}`], { cwd: outputDir });
}

function buildMacWithPackager(target, outputDir) {
  const stageDir = stagePackagerApp(target);
  ensureElectronZip('darwin', target.electronArch);
  const args = [
    stageDir,
    PRODUCT_NAME,
    `--platform=darwin`,
    `--arch=${target.electronArch}`,
    `--out=${outputDir}`,
    '--overwrite',
    '--asar',
    `--app-version=${DESKTOP_MANIFEST.version}`,
    `--electron-version=${ELECTRON_VERSION}`,
    `--electron-zip-dir=${ELECTRON_CACHE_DIR}`,
    `--app-bundle-id=${APP_ID}`,
    `--icon=${path.resolve(ROOT_DIR, 'icon.icns')}`,
    `--extra-resource=${DESKTOP_BACKEND_STAGE_DIR}`,
    `--extra-resource=${DESKTOP_QDRANT_STAGE_DIR}`,
  ];
  runElectronPackager(args);
  if (process.platform === 'darwin') {
    createZipArtifact(outputDir, target);
    return;
  }
  console.warn(`==> [desktop:${target.id}] non-mac host detected, exporting .tar.gz to preserve macOS executable permissions`);
  createMacTarGzArtifact(outputDir, target);
}

function buildLinuxWithPackager(target, outputDir) {
  const stageDir = stagePackagerApp(target);
  ensureElectronZip('linux', target.electronArch);
  const args = [
    stageDir,
    PRODUCT_NAME,
    '--platform=linux',
    `--arch=${target.electronArch}`,
    `--out=${outputDir}`,
    '--overwrite',
    '--asar',
    `--app-version=${DESKTOP_MANIFEST.version}`,
    `--electron-version=${ELECTRON_VERSION}`,
    `--electron-zip-dir=${ELECTRON_CACHE_DIR}`,
    `--icon=${path.resolve(ROOT_DIR, 'icon.png')}`,
    `--extra-resource=${DESKTOP_BACKEND_STAGE_DIR}`,
    `--extra-resource=${DESKTOP_QDRANT_STAGE_DIR}`,
  ];
  runElectronPackager(args);
  createTarGzArtifact(outputDir, target);
}

function exportBackendIfRequested(target, jarPath, runtimeHome) {
  if (!EXPORT_BACKEND) {
    return;
  }
  const backendOutputDir = path.join(BACKEND_RELEASE_ROOT, target.id);
  console.log(`==> [backend:${target.id}] export`);
  prepareBackendRuntime(backendOutputDir, jarPath, runtimeHome);
}

function buildDesktopForTarget(target) {
  if (!INCLUDE_DESKTOP) {
    return;
  }

  console.log(`==> [qdrant:${target.id}] stage`);
  preparePackagedQdrant(target);
  console.log(`==> [desktop:${target.id}] output cleanup`);
  const outputDir = prepareDesktopOutputDir(target);

  // Keep macOS/Linux packaging on electron-packager to avoid host-specific
  // electron-builder failures on GitHub runners.
  if (target.electronPlatform === 'mac') {
    console.log(`==> [desktop:${target.id}] electron-packager`);
    buildMacWithPackager(target, outputDir);
    ensureArtifactsExist(target, outputDir);
    return;
  }

  if (target.electronPlatform === 'linux') {
    console.log(`==> [desktop:${target.id}] electron-packager`);
    buildLinuxWithPackager(target, outputDir);
    ensureArtifactsExist(target, outputDir);
    return;
  }

  console.log(`==> [desktop:${target.id}] electron-builder`);
  const args = [
    'electron-builder',
    `--config.directories.output=${outputDir}`,
    ...target.builderArgs,
  ];
  if (ELECTRON_DIST) {
    args.push(`--config.electronDist=${ELECTRON_DIST}`);
  }
  const env = {};
  if (SHOULD_DISABLE_MAC_SIGN) {
    env.CSC_IDENTITY_AUTO_DISCOVERY = 'false';
  }
  runElectronBuilderCommand(target.id, args, {
    cwd: DESKTOP_DIR,
    env,
  }, outputDir);
  ensureArtifactsExist(target, outputDir);
}

function printSummary(targets) {
  for (const target of targets) {
    if (INCLUDE_DESKTOP) {
      console.log(`Desktop artifact exported under ${path.join('release', 'desktop', target.id)}`);
    }
    if (EXPORT_BACKEND) {
      console.log(`Backend runtime exported under ${path.join('release', 'backend', target.id)}`);
    }
  }
}

function main() {
  const targets = parseTargets(process.argv.slice(2));
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  fs.mkdirSync(TOOLCHAIN_CACHE_DIR, { recursive: true });
  removePathWithRetry(TEMP_DIR);
  cleanupStageDir();

  try {
    buildDesktopTypeCheckOnce();
    buildDesktopAssetsOnce();

    for (const target of targets) {
      console.log(`==> [target] ${target.id}`);
      const jarPath = buildBackend(target);
      ensureQdrantBinary(target);
      const runtimeHome = resolveBundledRuntimeHome(target, jarPath);
      exportBackendIfRequested(target, jarPath, runtimeHome);
      prepareBackendRuntime(DESKTOP_BACKEND_STAGE_DIR, jarPath, runtimeHome);
      buildDesktopForTarget(target);
    }

    printSummary(targets);
  } finally {
    cleanupStageDir();
  }
}

main();

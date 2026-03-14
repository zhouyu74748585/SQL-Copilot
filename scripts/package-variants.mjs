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
const RELEASE_DIR = path.join(ROOT_DIR, 'release');
const TEMP_DIR = path.join(RELEASE_DIR, '.jlink-temp');
const DEFAULT_VARIANTS = ['minimal', 'medium', 'full'];
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
  const result = spawnSync(command, args, {
    cwd,
    env: env ? { ...process.env, ...env } : process.env,
    encoding: 'utf8',
    stdio: captureOutput ? 'pipe' : 'inherit',
    shell: useShell,
  });
  return result;
}

function formatCommandFailure(command, args, result, captured) {
  const commandLine = [command, ...args].join(' ');
  if (!captured) {
    return `${commandLine}\nExit code ${result.status ?? 'unknown'}`;
  }
  const stderr = (result.stderr || '').trim();
  const stdout = (result.stdout || '').trim();
  const detail = [stdout, stderr].filter(Boolean).join('\n');
  return detail ? `${commandLine}\n${detail}` : `${commandLine}\nExit code ${result.status ?? 'unknown'}`;
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

function runElectronBuilderCommand(variant, args, options, desktopOutputDir) {
  const maxAttempts = process.platform === 'win32' && variant === 'full' ? 2 : 1;
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
        console.warn(`[windows-rcedit] Detected commit issue for ${variant}, rebuilding executable from a clean output directory (attempt ${attempt + 1}/${maxAttempts})`);
        ensureCleanDir(desktopOutputDir);
        continue;
      }
      throw lastFailure;
    }

    if (commitIssueDetected) {
      if (attempt < maxAttempts) {
        console.warn(`[windows-rcedit] Detected commit issue for ${variant}, rebuilding executable from a clean output directory (attempt ${attempt + 1}/${maxAttempts})`);
        ensureCleanDir(desktopOutputDir);
        continue;
      }
      console.warn(`[windows-rcedit] Commit issue still detected for ${variant} after ${maxAttempts} attempts; keeping the last successful artifact for manual verification.`);
    }

    return result;
  }

  if (lastFailure) {
    throw lastFailure;
  }

  throw new Error(`electron-builder failed for ${variant}`);
}

function normalizeVariant(raw) {
  const value = (raw || '').trim().toLowerCase();
  return DEFAULT_VARIANTS.includes(value) ? value : '';
}

function parseVariants(argv) {
  const variants = [];
  const rawItems = argv.length > 0
    ? argv
    : ((process.env.SQLCOPILOT_VARIANTS || '').trim()
        ? process.env.SQLCOPILOT_VARIANTS.split(',')
        : DEFAULT_VARIANTS);

  for (const raw of rawItems) {
    const normalized = normalizeVariant(raw);
    if (!normalized) {
      throw new Error(`Invalid variant: ${raw}. Allowed values: minimal|medium|full`);
    }
    if (!variants.includes(normalized)) {
      variants.push(normalized);
    }
  }
  return variants;
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

function isWindowsBusyError(error) {
  return process.platform === 'win32' && ['EBUSY', 'EPERM', 'ENOTEMPTY'].includes(error?.code);
}

function cleanupStageDir() {
  ensureEmptyDir(DESKTOP_BACKEND_STAGE_DIR);
  fs.writeFileSync(path.join(DESKTOP_BACKEND_STAGE_DIR, '.gitkeep'), '', 'utf8');
}

function copyIfExists(sourcePath, targetPath) {
  if (fs.existsSync(sourcePath)) {
    fs.copyFileSync(sourcePath, targetPath);
  }
}

function copyDirIfExists(sourceDir, targetDir) {
  if (fs.existsSync(sourceDir)) {
    fs.cpSync(sourceDir, targetDir, { recursive: true });
  }
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

function locatePackagedJar() {
  const jars = fs.existsSync(SERVER_TARGET_DIR)
    ? fs.readdirSync(SERVER_TARGET_DIR)
        .filter((name) => name.endsWith('.jar') && !name.startsWith('original-'))
        .sort()
    : [];
  if (!jars.length) {
    throw new Error(`No packaged jar found in ${SERVER_TARGET_DIR}`);
  }
  return path.join(SERVER_TARGET_DIR, jars[0]);
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

function createJlinkRuntime(variant, jarPath) {
  const jarCommand = resolveJavaTool('jar');
  const jlinkCommand = resolveJavaTool('jlink');
  const variantTempDir = path.join(TEMP_DIR, `${variant}-backend`);
  const unpackedDir = path.join(variantTempDir, 'unpacked');
  const runtimeDir = path.join(variantTempDir, 'runtime');

  ensureCleanDir(unpackedDir);
  fs.mkdirSync(TEMP_DIR, { recursive: true });
  runCommand(jarCommand, ['xf', jarPath], { cwd: unpackedDir });

  const modules = [...detectJlinkModules(unpackedDir)].sort();
  const moduleList = modules.join(',');
  console.log(`==> [${variant}] jlink runtime`);
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

function writeBackendLaunchScripts(targetDir, variant) {
  const runSh = `#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILE="\${1:-${variant}}"
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

exec "$JAVA_BIN" -Dfile.encoding=UTF-8 -jar "$JAR_FILE" --spring.profiles.active="$PROFILE"
`;

  const runCmd = `@echo off
setlocal enabledelayedexpansion
set "BASE_DIR=%~dp0"
set "PROFILE=%~1"
if "%PROFILE%"=="" set "PROFILE=${variant}"
set "JAVA_BIN=%BASE_DIR%jre\\bin\\java.exe"
if defined SQLCOPILOT_JAVA_BIN set "JAVA_BIN=%SQLCOPILOT_JAVA_BIN%"
if not defined SQLCOPILOT_DATA_DIR set "SQLCOPILOT_DATA_DIR=%LOCALAPPDATA%\\SQL Copilot"

if not exist "%JAVA_BIN%" (
  echo Bundled Java runtime not found: %JAVA_BIN%
  exit /b 1
)

if not exist "%SQLCOPILOT_DATA_DIR%" mkdir "%SQLCOPILOT_DATA_DIR%"

for %%f in ("%BASE_DIR%*.jar") do (
  "%JAVA_BIN%" -Dfile.encoding=UTF-8 -jar "%%f" --spring.profiles.active=%PROFILE%
  exit /b !ERRORLEVEL!
)

echo No backend jar found in %BASE_DIR%
exit /b 1
`;

  fs.writeFileSync(path.join(targetDir, 'run.sh'), runSh, 'utf8');
  fs.writeFileSync(path.join(targetDir, 'run.cmd'), runCmd, 'utf8');
  if (process.platform !== 'win32') {
    fs.chmodSync(path.join(targetDir, 'run.sh'), 0o755);
  }
}

function prepareBackendRuntime(targetDir, variant, jarPath, runtimeDir) {
  ensureEmptyDir(targetDir);
  fs.copyFileSync(jarPath, path.join(targetDir, path.basename(jarPath)));
  fs.cpSync(runtimeDir, path.join(targetDir, 'jre'), { recursive: true });
  copyIfExists(path.join(SERVER_DIR, 'src', 'main', 'resources', 'application.yml'), path.join(targetDir, 'application.yml'));
  copyIfExists(path.join(SERVER_DIR, 'src', 'main', 'resources', `application-${variant}.yml`), path.join(targetDir, `application-${variant}.yml`));
  writeBackendLaunchScripts(targetDir, variant);
  fs.writeFileSync(path.join(targetDir, 'variant'), `${variant}\n`, 'utf8');

  if (variant === 'full') {
    copyDirIfExists(path.join(SERVER_DIR, 'models'), path.join(targetDir, 'models'));
  }
}

function buildBackendVariant(variant) {
  console.log(`==> [${variant}] backend clean package`);
  runCommand(resolveShellCommand('mvn'), [
    '-f',
    path.join('apps', 'server', 'pom.xml'),
    `-Ppack-${variant}`,
    'clean',
    'package',
    '-DskipTests',
  ]);
  const jarPath = locatePackagedJar();
  const runtimeDir = createJlinkRuntime(variant, jarPath);
  return { jarPath, runtimeDir };
}

function buildDesktopTypeCheckOnce() {
  if (!INCLUDE_DESKTOP) {
    return;
  }
  console.log('==> [desktop] type-check');
  runCommand(resolveShellCommand('npm'), ['run', '-w', '@sqlcopilot/desktop', 'type-check']);
}

function prepareDesktopOutputDir(variant) {
  const desktopReleaseDir = path.join(RELEASE_DIR, variant, 'desktop');
  try {
    ensureCleanDir(desktopReleaseDir);
    return desktopReleaseDir;
  } catch (error) {
    if (!isWindowsBusyError(error)) {
      throw error;
    }

    const fallbackDir = path.join(
      RELEASE_DIR,
      variant,
      `desktop-${new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)}`,
    );
    console.warn(`[windows-lock] ${desktopReleaseDir} is still locked, fallback to ${fallbackDir}`);
    ensureCleanDir(fallbackDir);
    return fallbackDir;
  }
}

function buildDesktopVariant(variant) {
  if (!INCLUDE_DESKTOP) {
    return;
  }

  console.log(`==> [${variant}] desktop build`);
  runCommand(resolveShellCommand('npm'), ['run', '-w', '@sqlcopilot/desktop', `build:${variant}`]);

  console.log(`==> [${variant}] desktop output cleanup`);
  const desktopOutputDir = prepareDesktopOutputDir(variant);

  console.log(`==> [${variant}] electron-builder`);
  const args = [
    'electron-builder',
    `--config.directories.output=${desktopOutputDir}`,
  ];
  if (process.platform === 'win32' && variant === 'full') {
    args.push('--win', 'zip');
  }
  if (ELECTRON_DIST) {
    args.push(`--config.electronDist=${ELECTRON_DIST}`);
  }
  const env = {
    SQLCOPILOT_PACKAGE_VARIANT: variant,
  };
  if (SHOULD_DISABLE_MAC_SIGN) {
    env.CSC_IDENTITY_AUTO_DISCOVERY = 'false';
  }
  runElectronBuilderCommand(variant, args, {
    cwd: DESKTOP_DIR,
    env,
  }, desktopOutputDir);
}

function removeReleaseDirIfDisabled(variant) {
  if (!EXPORT_BACKEND) {
    removePathWithRetry(path.join(RELEASE_DIR, variant, 'backend'));
  }
  if (!INCLUDE_DESKTOP) {
    removePathWithRetry(path.join(RELEASE_DIR, variant, 'desktop'));
  }
}

function printSummary() {
  if (INCLUDE_DESKTOP && EXPORT_BACKEND) {
    console.log('All variants packaged under release/{minimal,medium,full}/{backend,desktop}');
    return;
  }
  if (INCLUDE_DESKTOP) {
    console.log('Desktop variants packaged under release/{minimal,medium,full}/desktop (backend bundled with jlink runtime)');
    return;
  }
  if (EXPORT_BACKEND) {
    console.log('Backend runtime variants exported under release/{minimal,medium,full}/backend');
    return;
  }
  console.log('No release artifacts exported (backend build executed as intermediate only)');
}

function main() {
  const variants = parseVariants(process.argv.slice(2));
  fs.mkdirSync(RELEASE_DIR, { recursive: true });
  removePathWithRetry(TEMP_DIR);
  cleanupStageDir();

  try {
    buildDesktopTypeCheckOnce();

    for (const variant of variants) {
      removeReleaseDirIfDisabled(variant);

      const { jarPath, runtimeDir } = buildBackendVariant(variant);
      if (INCLUDE_DESKTOP) {
        prepareBackendRuntime(DESKTOP_BACKEND_STAGE_DIR, variant, jarPath, runtimeDir);
      } else {
        cleanupStageDir();
      }
      if (EXPORT_BACKEND) {
        prepareBackendRuntime(path.join(RELEASE_DIR, variant, 'backend'), variant, jarPath, runtimeDir);
      }

      buildDesktopVariant(variant);
    }

    printSummary();
  } finally {
    cleanupStageDir();
  }
}

main();

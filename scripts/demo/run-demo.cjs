const fs = require('fs');
const path = require('path');
const {spawn} = require('child_process');
const {
  ROOT,
  PREVIEW_URL,
  BACKEND_HEALTH_URL,
  parseArgs,
  ensureDir,
  resetDir,
  waitForUrl,
  nowTimestamp,
  commandPreview,
  terminateProcessTree,
  assertPortAvailable,
} = require('../acceptance/lib/common.cjs');

const args = parseArgs(process.argv.slice(2));
const runId = (args.run || nowTimestamp()).trim();
const outputDir = path.resolve(args['output-dir'] || path.join(ROOT, 'output', 'playwright', 'mysql-demo', runId));
const logsDir = path.join(outputDir, 'logs');

function resolveSpawnSpec(command, commandArgs) {
  if (process.platform === 'win32' && /\.(cmd|bat)$/i.test(command)) {
    return {
      command: 'cmd.exe',
      args: ['/d', '/s', '/c', commandPreview(command, commandArgs)],
    };
  }
  return {
    command,
    args: commandArgs,
  };
}

function spawnLogged(command, commandArgs, options = {}) {
  const logFile = options.logFile || path.join(logsDir, 'run.log');
  ensureDir(path.dirname(logFile));
  const stream = fs.createWriteStream(logFile, {flags: 'a'});
  stream.write(`$ ${commandPreview(command, commandArgs)}\n`);
  const spec = resolveSpawnSpec(command, commandArgs);
  const child = spawn(spec.command, spec.args, {
    cwd: options.cwd || ROOT,
    env: {
      ...process.env,
      ...(options.env || {}),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    shell: false,
  });
  child.stdout.on('data', (chunk) => stream.write(chunk));
  child.stderr.on('data', (chunk) => stream.write(chunk));
  child.on('exit', (code, signal) => {
    stream.write(`\n[exit] code=${code} signal=${signal}\n`);
    stream.end();
  });
  return child;
}

function runCommand(command, commandArgs, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawnLogged(command, commandArgs, options);
    child.on('error', reject);
    child.on('exit', (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(`Command failed: ${commandPreview(command, commandArgs)}`));
    });
  });
}

function resolveServerJar() {
  const targetDir = path.join(ROOT, 'apps', 'server', 'target');
  const jars = fs.readdirSync(targetDir).filter((item) => item.endsWith('.jar') && item.startsWith('sql-copilot-server'));
  if (!jars.length) {
    throw new Error('Backend jar was not found after packaging.');
  }
  jars.sort();
  return path.join(targetDir, jars[jars.length - 1]);
}

function resolveJavaCommand() {
  const javaHome = (process.env.JAVA_HOME || '').trim();
  if (javaHome) {
    const candidate = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return process.platform === 'win32' ? 'java.bat' : 'java';
}

async function main() {
  await assertPortAvailable(18080);
  await assertPortAvailable(8888);

  resetDir(outputDir);
  ensureDir(logsDir);

  await runCommand('mvn.cmd', [
    '-f',
    'apps/server/pom.xml',
    'clean',
    'package',
    '-DskipTests',
    '-Dfile.encoding=UTF-8',
  ], {
    logFile: path.join(logsDir, 'backend-package.log'),
  });

  const backendJar = resolveServerJar();
  const javaCommand = resolveJavaCommand();
  const backendProc = spawnLogged(javaCommand, [
    '-Dfile.encoding=UTF-8',
    '-jar',
    backendJar,
  ], {
    env: {
      SQLCOPILOT_DATA_DIR: ROOT,
    },
    logFile: path.join(logsDir, 'backend-runtime.log'),
  });

  let previewProc = null;
  try {
    await waitForUrl(BACKEND_HEALTH_URL, {
      timeoutMs: 90_000,
      validate: (body) => body.includes('"ok"'),
    });

    await runCommand('npm.cmd', ['run', 'build'], {
      logFile: path.join(logsDir, 'frontend-build.log'),
    });

    previewProc = spawnLogged('npm.cmd', [
      'run',
      '-w',
      '@sqlcopilot/desktop',
      'preview',
      '--',
      '--host',
      '127.0.0.1',
      '--port',
      '8888',
      '--strictPort',
    ], {
      logFile: path.join(logsDir, 'preview.log'),
    });

    await waitForUrl(PREVIEW_URL, {
      timeoutMs: 90_000,
      validate: (body) => body.includes('<div id="app">'),
    });

    await runCommand('node', [
      'scripts/demo/run-electron-demo.cjs',
      `--output-dir=${outputDir}`,
    ], {
      logFile: path.join(logsDir, 'electron-demo.log'),
    });

    await runCommand('python', [
      'scripts/demo/build-demo-gif.py',
      '--output-dir',
      outputDir,
    ], {
      logFile: path.join(logsDir, 'gif.log'),
    });

    const gifPath = path.join(outputDir, 'demo.gif');
    if (!fs.existsSync(gifPath)) {
      throw new Error(`GIF was not generated: ${gifPath}`);
    }
    process.stdout.write(`[mysql-demo] completed: ${outputDir}\n`);
  } finally {
    if (previewProc?.pid) {
      terminateProcessTree(previewProc.pid, 'preview');
    }
    if (backendProc?.pid) {
      terminateProcessTree(backendProc.pid, 'backend');
    }
  }
}

main().catch((error) => {
  process.stderr.write(`${error?.stack || error}\n`);
  process.exit(1);
});

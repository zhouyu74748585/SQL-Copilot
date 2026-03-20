const fs = require('fs');
const path = require('path');
const {spawn} = require('child_process');
const {
  ROOT,
  OUTPUT_ROOT,
  PREVIEW_URL,
  BACKEND_HEALTH_URL,
  parseArgs,
  ensureDir,
  resetDir,
  waitForUrl,
  writeJson,
  writeText,
  readJsonIfExists,
  nowTimestamp,
  relativeFromRoot,
  commandPreview,
  terminateProcessTree,
  assertPortAvailable,
} = require('./lib/common.cjs');

const args = parseArgs(process.argv.slice(2));
const round = Number.parseInt(args.round || '1', 10);

if (!Number.isFinite(round) || round < 1 || round > 3) {
  throw new Error('Round must be an integer between 1 and 3.');
}

const roundDir = path.join(OUTPUT_ROOT, `round-${round}`);
const logsDir = path.join(roundDir, 'logs');

function issueKeyForScenario(scenarioId) {
  return `acceptance:${scenarioId}`;
}

function makeResult({ priority, module, scenario, status, summary, evidence = [], issueKey = '', fixedInRound = null }) {
  return {
    round,
    module,
    scenario,
    priority,
    status,
    summary,
    evidence,
    issueKey,
    fixedInRound,
  };
}

function spawnLogged(command, commandArgs, options = {}) {
  ensureDir(path.dirname(options.logFile));
  const logStream = fs.createWriteStream(options.logFile, { flags: 'a' });
  logStream.write(`$ ${commandPreview(command, commandArgs)}\n`);
  const spawnSpec = resolveSpawnSpec(command, commandArgs);
  const child = spawn(spawnSpec.command, spawnSpec.args, {
    cwd: options.cwd || ROOT,
    env: {
      ...process.env,
      ...(options.env || {}),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: false,
    windowsHide: true,
  });
  child.stdout.on('data', (chunk) => {
    logStream.write(chunk);
  });
  child.stderr.on('data', (chunk) => {
    logStream.write(chunk);
  });
  child.on('exit', (code, signal) => {
    logStream.write(`\n[exit] code=${code} signal=${signal}\n`);
    logStream.end();
  });
  return child;
}

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

function buildSummary(results) {
  const passCount = results.filter((item) => item.status === 'PASS').length;
  const failCount = results.filter((item) => item.status === 'FAIL').length;
  const blockedCount = results.filter((item) => item.status === 'BLOCKED').length;
  return [
    '# Acceptance Report',
    '',
    `- 轮次：Round ${round}`,
    `- 生成时间：${nowTimestamp()}`,
    `- PASS：${passCount}`,
    `- FAIL：${failCount}`,
    `- BLOCKED：${blockedCount}`,
    '',
    '## Results',
    '',
    ...results.map((item) => `- [${item.status}] ${item.priority} ${item.module}/${item.scenario}: ${item.summary}`),
    '',
  ].join('\n');
}

function mergeFixedRound(results, previousResults) {
  const previousMap = new Map((previousResults || []).map((item) => [item.scenario, item]));
  return results.map((item) => {
    const previous = previousMap.get(item.scenario);
    if (!previous) {
      return item;
    }
    if (previous.status === 'FAIL' && item.status === 'PASS') {
      return {
        ...item,
        fixedInRound: round,
      };
    }
    return item;
  });
}

async function main() {
  await assertPortAvailable(18080);
  await assertPortAvailable(8888);

  resetDir(roundDir);
  ensureDir(logsDir);

  const baselineResults = [];
  const previousResults = round > 1
    ? readJsonIfExists(path.join(OUTPUT_ROOT, `round-${round - 1}`, 'acceptance-results.json'))
    : null;

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
  const backendLogFile = path.join(logsDir, 'backend-runtime.log');
  const backendProc = spawnLogged(javaCommand, [
    '-Dfile.encoding=UTF-8',
    '-jar',
    backendJar,
  ], {
    env: {
      SQLCOPILOT_DATA_DIR: ROOT,
    },
    logFile: backendLogFile,
  });

  const previewLogFile = path.join(logsDir, 'preview.log');
  let previewProc = null;

  try {
    await waitForUrl(BACKEND_HEALTH_URL, {
      timeoutMs: 60_000,
      validate: (body) => body.includes('"ok"'),
    });
    baselineResults.push(makeResult({
      priority: 'P0',
      module: 'startup',
      scenario: 'backend-health',
      status: 'PASS',
      summary: '后端 clean 打包并启动成功，/api/health 返回 ok。',
      evidence: [relativeFromRoot(backendLogFile)],
    }));

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
      logFile: previewLogFile,
    });

    await waitForUrl(PREVIEW_URL, {
      timeoutMs: 60_000,
      validate: (body) => body.includes('<div id="app">'),
    });
    baselineResults.push(makeResult({
      priority: 'P0',
      module: 'startup',
      scenario: 'frontend-preview',
      status: 'PASS',
      summary: '前端 clean build 后 preview 成功，8888 端口可访问。',
      evidence: [
        relativeFromRoot(previewLogFile),
        relativeFromRoot(path.join(logsDir, 'frontend-build.log')),
      ],
    }));

    await runCommand('node', [
      'scripts/acceptance/run-electron-acceptance.cjs',
      `--round=${round}`,
      `--output-dir=${roundDir}`,
    ], {
      logFile: path.join(logsDir, 'electron.log'),
    });

    const electronResults = readJsonIfExists(path.join(roundDir, 'electron-results.json')) || [];
    const allResults = mergeFixedRound([...baselineResults, ...electronResults], previousResults);
    writeJson(path.join(roundDir, 'acceptance-results.json'), allResults);
    writeText(path.join(roundDir, 'summary.md'), `${buildSummary(allResults)}\n`);

    const failCount = allResults.filter((item) => item.status === 'FAIL').length;
    const blockedCount = allResults.filter((item) => item.status === 'BLOCKED').length;
    console.log(`[acceptance] Round ${round} complete. FAIL=${failCount} BLOCKED=${blockedCount}`);
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
  const fatal = [
    makeResult({
      priority: 'P0',
      module: 'startup',
      scenario: 'orchestration',
      status: 'FAIL',
      summary: error instanceof Error ? error.message : String(error),
      evidence: [],
      issueKey: issueKeyForScenario('orchestration'),
    }),
  ];
  ensureDir(roundDir);
  writeJson(path.join(roundDir, 'acceptance-results.json'), fatal);
  writeText(path.join(roundDir, 'summary.md'), `${buildSummary(fatal)}\n`);
  console.error(error);
  process.exit(1);
});

const { spawn } = require('child_process');
const path = require('path');

const electronBinary = require('electron');
const rawArgs = process.argv.slice(2);
const env = { ...process.env };
const electronArgs = [];

delete env.ELECTRON_RUN_AS_NODE;

for (const arg of rawArgs) {
  if (arg === '--debug') {
    env.ELECTRON_DEBUG = '1';
    continue;
  }
  electronArgs.push(arg);
}

if (!env.ELECTRON_RENDERER_URL) {
  env.ELECTRON_RENDERER_URL = 'http://127.0.0.1:8888';
}

electronArgs.push(path.join(__dirname, '..', 'electron', 'main.cjs'));

const child = spawn(electronBinary, electronArgs, {
  env,
  stdio: 'inherit',
});

child.on('exit', (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code ?? 0);
});


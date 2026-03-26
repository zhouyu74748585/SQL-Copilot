import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const DESKTOP_DIR = path.resolve(SCRIPT_DIR, '..');
const SOURCE_DIR = path.join(DESKTOP_DIR, 'resources', 'qdrant');
const STAGE_ROOT_DIR = path.join(DESKTOP_DIR, 'resources', 'qdrant-package');
const STAGE_DIR = path.join(STAGE_ROOT_DIR, 'qdrant');

function currentPlatformKey() {
  if (process.platform === 'win32') {
    return process.arch === 'arm64' ? 'win32-arm64' : 'win32-x64';
  }
  if (process.platform === 'darwin') {
    return process.arch === 'arm64' ? 'darwin-arm64' : 'darwin-x64';
  }
  return process.arch === 'arm64' ? 'linux-arm64' : 'linux-x64';
}

function parseTarget(argv) {
  const arg = argv.find((item) => item.startsWith('--target='));
  return (arg ? arg.slice('--target='.length) : currentPlatformKey()).trim();
}

function ensureCleanDir(targetDir) {
  fs.rmSync(targetDir, { recursive: true, force: true });
  fs.mkdirSync(targetDir, { recursive: true });
}

function main() {
  const target = parseTarget(process.argv.slice(2));
  const sourceTargetDir = path.join(SOURCE_DIR, target);
  if (!fs.existsSync(sourceTargetDir)) {
    throw new Error(`Missing qdrant resources for ${target}: ${sourceTargetDir}`);
  }

  ensureCleanDir(STAGE_ROOT_DIR);
  fs.cpSync(sourceTargetDir, path.join(STAGE_DIR, target), { recursive: true });
  console.log(STAGE_DIR);
}

main();

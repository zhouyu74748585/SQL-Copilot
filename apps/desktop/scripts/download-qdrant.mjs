import fs from 'node:fs';
import path from 'node:path';
import {execSync} from 'node:child_process';

const releaseTag = process.env.QDRANT_VERSION || 'v1.13.4';
const cwd = process.cwd();
const platform = process.platform;
const arch = process.arch;

const platformKey = (() => {
  if (platform === 'darwin') {
    return arch === 'arm64' ? 'darwin-arm64' : 'darwin-x64';
  }
  if (platform === 'win32') {
    return arch === 'arm64' ? 'win32-arm64' : 'win32-x64';
  }
  return arch === 'arm64' ? 'linux-arm64' : 'linux-x64';
})();

const targetDir = path.join(cwd, 'resources', 'qdrant', platformKey);
const binaryName = platform === 'win32' ? 'qdrant.exe' : 'qdrant';
const targetBinary = path.join(targetDir, binaryName);
const tempDir = path.join(cwd, '.qdrant-download-tmp');

fs.mkdirSync(targetDir, { recursive: true });
fs.rmSync(tempDir, { recursive: true, force: true });
fs.mkdirSync(tempDir, { recursive: true });

const releaseApi = `https://api.github.com/repos/qdrant/qdrant/releases/tags/${releaseTag}`;
console.log(`[qdrant-download] release=${releaseTag}, platform=${platformKey}`);
const releaseJson = execSync(`curl -fsSL ${releaseApi}`, { encoding: 'utf8' });
const release = JSON.parse(releaseJson);
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
const candidate = assets.find((asset) => {
  const lower = String(asset.name || '').toLowerCase();
  return keywords.every((kw) => lower.includes(kw))
    && (lower.endsWith('.tar.gz') || lower.endsWith('.zip'));
});

if (!candidate) {
  throw new Error(`No matching qdrant asset found for ${platformKey} in release ${releaseTag}`);
}

const archivePath = path.join(tempDir, candidate.name);
console.log(`[qdrant-download] downloading ${candidate.browser_download_url}`);
execSync(`curl -fL ${candidate.browser_download_url} -o ${archivePath}`, { stdio: 'inherit' });

if (archivePath.endsWith('.tar.gz')) {
  execSync(`tar -xzf ${archivePath} -C ${tempDir}`, { stdio: 'inherit' });
} else if (archivePath.endsWith('.zip')) {
  execSync(`unzip -o ${archivePath} -d ${tempDir}`, { stdio: 'inherit' });
} else {
  throw new Error(`Unsupported archive type: ${archivePath}`);
}

function findBinary(rootDir, fileName) {
  const entries = fs.readdirSync(rootDir, { withFileTypes: true });
  for (const entry of entries) {
    const abs = path.join(rootDir, entry.name);
    if (entry.isFile() && entry.name === fileName) {
      return abs;
    }
    if (entry.isDirectory()) {
      const nested = findBinary(abs, fileName);
      if (nested) {
        return nested;
      }
    }
  }
  return null;
}

const downloadedBinary = findBinary(tempDir, binaryName);
if (!downloadedBinary) {
  throw new Error(`Unable to find ${binaryName} in downloaded archive`);
}

fs.copyFileSync(downloadedBinary, targetBinary);
if (platform !== 'win32') {
  fs.chmodSync(targetBinary, 0o755);
}

console.log(`[qdrant-download] installed: ${targetBinary}`);
fs.rmSync(tempDir, { recursive: true, force: true });

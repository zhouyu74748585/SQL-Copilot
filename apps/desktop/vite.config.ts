import path from 'node:path';
import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';

const SAFE_BUILD = process.env.SQLCOPILOT_VITE_SAFE_BUILD === '1';

function resolveManualChunk(id: string) {
  const normalized = id.split(path.sep).join('/');
  if (!normalized.includes('/node_modules/')) {
    return undefined;
  }
  if (normalized.includes('/node_modules/monaco-editor/')
    || normalized.includes('/node_modules/@guolao/vue-monaco-editor/')) {
    return 'vendor-monaco';
  }
  if (normalized.includes('/node_modules/ant-design-vue/')
    || normalized.includes('/node_modules/@ant-design/icons-vue/')) {
    return 'vendor-antdv';
  }
  if (normalized.includes('/node_modules/echarts/')) {
    return 'vendor-charts';
  }
  if (normalized.includes('/node_modules/sql-formatter/')) {
    return 'vendor-sql';
  }
  if (normalized.includes('/node_modules/vue/')
    || normalized.includes('/node_modules/@vue/')) {
    return 'vendor-vue';
  }
  return 'vendor-misc';
}

export default defineConfig(({ command }) => ({
  // Electron production uses file:// to load dist/index.html.
  // Build with relative asset paths to avoid resolving to file:///assets/*.
  base: command === 'build' ? './' : '/',
  plugins: [vue()],
  build: {
    cssMinify: SAFE_BUILD ? false : undefined,
    minify: SAFE_BUILD ? false : 'esbuild',
    reportCompressedSize: !SAFE_BUILD,
    rollupOptions: {
      output: {
        manualChunks(id) {
          return resolveManualChunk(id);
        },
      },
    },
  },
  server: {
    host: '127.0.0.1',
    port: 8888,
    strictPort: true,
  },
}));

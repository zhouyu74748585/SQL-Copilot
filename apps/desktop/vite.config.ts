import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig(({ command }) => ({
  // Electron production uses file:// to load dist/index.html.
  // Build with relative asset paths to avoid resolving to file:///assets/*.
  base: command === 'build' ? './' : '/',
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 8888,
    strictPort: true,
  },
}));

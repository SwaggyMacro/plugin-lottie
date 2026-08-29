import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import Icons from 'unplugin-icons/vite'
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue(), Icons({ compiler: 'vue3' })],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    // Keep pnpm/node_modules backups out of test discovery. These folders can
    // contain upstream package fixtures that import dependencies unavailable
    // in the plugin workspace.
    exclude: [
      ...configDefaults.exclude,
      'e2e/**',
      'node_modules.backup-*/**',
      // Keep recoverable pnpm backup trees out of test discovery on Windows.
      'node_modules-*/**',
    ],
    root: fileURLToPath(new URL('./', import.meta.url)),
  },
})

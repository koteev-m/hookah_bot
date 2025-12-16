import { defineConfig } from 'vite'

export default defineConfig({
  base: '/miniapp/',
  server: {
    port: 5173,
    host: '0.0.0.0'
  },
  preview: {
    port: 4173,
    host: '0.0.0.0'
  },
  build: {
    outDir: 'dist'
  }
})

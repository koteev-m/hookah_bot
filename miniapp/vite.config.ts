import { defineConfig } from 'vite'
import { readFileSync } from 'node:fs'

export default defineConfig({
  base: '/miniapp/',
  esbuild: { keepNames: true },
  plugins: [{
    name: 'pdfjs-license',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'pdfjs-LICENSE.txt', source: readFileSync(new URL('./node_modules/pdfjs-dist/LICENSE', import.meta.url), 'utf8') })
    }
  }],
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

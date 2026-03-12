import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const host = process.env.TAURI_DEV_HOST

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  // 防止 vite 遮蔽 rust 错误
  clearScreen: false,

  server: {
    // 确保端口与 tauri.conf.json 中的 devUrl 匹配
    port: 5173,
    // Tauri 需要固定端口，如果端口被占用则失败
    strictPort: true,
    // 如果设置了 Tauri 期望的主机，使用它
    host: host || false,
    hmr: host ? {
      protocol: 'ws',
      host,
      port: 1421,
    } : undefined,
    watch: {
      // 告诉 vite 忽略监听 `src-tauri`
      ignored: ['**/src-tauri/**'],
    },
  },

  // 环境变量前缀
  envPrefix: ['VITE_', 'TAURI_ENV_*'],

  build: {
    // Tauri 在 Windows 上使用 Chromium，在 macOS 和 Linux 上使用 WebKit
    target:
      process.env.TAURI_ENV_PLATFORM == 'windows'
        ? 'chrome105'
        : 'safari13',
    // 调试构建时不压缩
    minify: !process.env.TAURI_ENV_DEBUG ? 'esbuild' : false,
    // 调试构建时生成 sourcemap
    sourcemap: !!process.env.TAURI_ENV_DEBUG,
  },
})

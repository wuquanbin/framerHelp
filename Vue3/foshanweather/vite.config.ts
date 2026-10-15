import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import type { ProxyOptions } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// 接口、上传文件、WebSocket 统一反代到本机后端。
// dev 和 preview 各要一份：vite preview 不会继承 server.proxy，
// 漏了它，手机打开 4173 上的后台页面时 /api 会全部 404。
const backendProxy: Record<string, ProxyOptions> = {
  '/api': {
    target: 'http://127.0.0.1:8082',
    changeOrigin: true,
  },
  '/uploads': {
    target: 'http://127.0.0.1:8082',
    changeOrigin: true,
  },
  '/ws': {
    target: 'ws://127.0.0.1:8082',
    ws: true,
    changeOrigin: true,
  },
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  // host: true 让服务器监听 0.0.0.0，手机用局域网 IP（192.168.x.x）才连得上；
  // allowedHosts: true 放开 Host 校验，用 IP 访问不会被 "Blocked request" 拦掉。
  // 前端一律用同源相对路径（各 .env 里 VITE_API_ORIGIN 留空），接口交给下面的 proxy 转发，
  // 所以电脑 IP 变了也只改小程序那一处，后台不用重新构建。
  server: {
    host: true,
    allowedHosts: true,
    proxy: backendProxy,
  },
  preview: {
    host: true,
    allowedHosts: true,
    port: 4173,
    strictPort: true,
    proxy: backendProxy,
  },
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 개발 중 화면은 같은 출처의 /api를 부르고, 여기서 백엔드로 넘긴다 (src/api/client.js의 API_URL 참고)
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})

import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Geliştirme: /api istekleri yerel backend'e (docker compose: 127.0.0.1:8081 -> 8080) yönlendirilir.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});

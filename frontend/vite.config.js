import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.AIRSENSE_API_TARGET || 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  build: { chunkSizeWarningLimit: 650 },
});

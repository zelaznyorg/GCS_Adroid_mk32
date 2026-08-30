import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // host: true -> serwer dostępny w całej sieci lokalnej (inne PC, telefony).
    host: true,
    port: 5173,
    proxy: {
      // Zapytania /api/* trafiają do backendu Node (port 3000).
      '/api': 'http://localhost:3000',
    },
  },
})

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // The backend has no CORS configuration of its own - it does not need one, since in a real
      // deployment this frontend is served from the same origin (or behind the same reverse
      // proxy). This proxy gives `npm run dev` the same same-origin shape without touching the
      // backend at all. See vestige.githubToken/webhookSecret-style "off by default" reasoning in
      // README for why nothing server-side was added just to make local frontend dev easier.
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})

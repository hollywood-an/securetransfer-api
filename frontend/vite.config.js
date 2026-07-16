import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Deployed to Vercel (production branch: master, root directory: frontend) —
// auto-deploys on push. See the live demo at securetransfer-demo.vercel.app.
// Vite dev server runs on http://localhost:5173 — the origin the backend's
// CORS config allows by default (see app.cors.allowed-origins).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
})

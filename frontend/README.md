# SecureTransfer — Frontend (Phase 9)

A small **Vite + React** demo UI for the SecureTransfer API. It's a *face* for the
existing backend — no new server logic. It calls the same REST endpoints, holds
the login JWT in memory, and sends it as `Authorization: Bearer …`.

## Four screens
1. **Login** — get a JWT (sign in as `admin` or `teller`).
2. **Account** — look up an account: balance, status, and its append-only ledger. (One-click "seed demo data" button opens a customer + two accounts.)
3. **Transfer** — move money; shows the success state *and* the rejected state (e.g. insufficient funds → 422).
4. **Fraud reviews** — the flagged-transfer queue with the AI agent's risk score, reasoning, and recommendation, plus **Approve / Hold / Escalate** (human-in-the-loop).

## Run it
```bash
cd frontend
npm install
npm run dev          # http://localhost:5173
```

By default it points at the **deployed** backend (`https://securetransfer-api.onrender.com`).
Change the **Backend** field in the header to `http://localhost:8080` to hit a
local backend, or set it at build time with `VITE_API_BASE`.

## CORS
Browsers block cross-origin API calls unless the backend allows the frontend's
origin. The backend allows `http://localhost:5173` by default
(`app.cors.allowed-origins`). If you **deploy** this frontend (e.g. Vercel), add
its URL to the backend's `CORS_ALLOWED_ORIGINS` env var (comma-separated).

## Build / deploy (optional)
```bash
npm run build        # static site in dist/
```
Push `dist/` (or the folder) to Vercel or Netlify, pointed at your deployed
backend, and add that frontend URL to `CORS_ALLOWED_ORIGINS` on the backend.

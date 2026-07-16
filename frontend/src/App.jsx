import { useState } from 'react'
import LoginPanel from './components/LoginPanel.jsx'
import AccountPanel from './components/AccountPanel.jsx'
import TransferPanel from './components/TransferPanel.jsx'
import FraudReviewPanel from './components/FraudReviewPanel.jsx'

// The deployed backend. You can change it in the header field at runtime
// (e.g. http://localhost:8080 when running the backend locally). Override the
// default at build time with VITE_API_BASE.
const DEFAULT_API = import.meta.env.VITE_API_BASE || 'https://securetransfer-api.onrender.com'

export default function App() {
  const [baseUrl, setBaseUrl] = useState(DEFAULT_API)
  const [auth, setAuth] = useState(null)         // { token, role, username, tenant } — kept in memory only
  const [demo, setDemo] = useState(null)         // { customerId, accountA, accountB } from the seed button
  const [refreshKey, setRefreshKey] = useState(0) // bump to reload the fraud queue after a transfer

  function logout() { setAuth(null); setDemo(null) }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">◈</span>
          <span>SecureTransfer <span className="muted">Teller Console</span></span>
        </div>
        <div className="topbar-right">
          <label className="baseurl">
            <span className="muted small">Backend</span>
            <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} spellCheck={false} />
          </label>
          {auth && (
            <div className="userchip">
              <span>{auth.username}</span>
              <span className="role">{auth.role}</span>
              {auth.tenant === 'DEMO' && <span className="demo-badge" title="An isolated sandbox — not connected to the staff bank">DEMO BANK</span>}
              <button className="btn btn-ghost small" onClick={logout}>Sign out</button>
            </div>
          )}
        </div>
      </header>

      <main className="content">
        {!auth ? (
          <LoginPanel baseUrl={baseUrl} onLogin={setAuth} />
        ) : (
          <div className="grid">
            <AccountPanel baseUrl={baseUrl} auth={auth} onSeed={setDemo} />
            <TransferPanel
              baseUrl={baseUrl}
              auth={auth}
              demo={demo}
              onTransferred={() => setRefreshKey((k) => k + 1)}
            />
            <FraudReviewPanel baseUrl={baseUrl} auth={auth} refreshKey={refreshKey} />
          </div>
        )}
      </main>

      <footer className="foot muted small">
        A demo UI for the SecureTransfer API — auth (JWT) · atomic transfers · double-entry ledger · agentic fraud triage with human-in-the-loop.
      </footer>
    </div>
  )
}

import { useState } from 'react'
import { apiRequest } from '../api.js'

// The public demo login: a TELLER in its own isolated DEMO bank, so it's safe to
// publish. It matches DEMO_PASSWORD's default in DataInitializer.
const DEMO_USERNAME = 'demo'
const DEMO_PASSWORD = 'demopass123'

// Screen 1 — Login. Sends username/password to POST /auth/login, and on success
// hands the token + role back up to <App> (which holds it in memory).
export default function LoginPanel({ baseUrl, onLogin }) {
  const [username, setUsername] = useState(DEMO_USERNAME)
  const [password, setPassword] = useState(DEMO_PASSWORD)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    const { ok, data } = await apiRequest(baseUrl, {
      method: 'POST',
      path: '/auth/login',
      body: { username, password },
    })
    setBusy(false)
    if (ok) {
      onLogin({ token: data.token, role: data.role, username, tenant: data.tenant })
    } else {
      setError(data?.message || 'Login failed')
    }
  }

  return (
    <div className="card login-card">
      <h2>Sign in</h2>
      <p className="muted">
        Open accounts, move money, and work the AI fraud-review queue. The demo login runs
        in its own sandbox bank, so try anything.
      </p>
      <div className="demo-creds">
        <div className="demo-creds-title">Public demo login</div>
        <div className="demo-creds-row">
          <span className="muted">Username</span> <code>{DEMO_USERNAME}</code>
          <span className="muted">Password</span> <code>{DEMO_PASSWORD}</code>
        </div>
      </div>
      <form onSubmit={submit}>
        <div className="field">
          <label>Username</label>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
        </div>
        <div className="field">
          <label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        {error && <div className="result-err">{error}</div>}
        <button className="btn btn-primary" type="submit" disabled={busy || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="muted small cold-start-note">
          The backend runs on a free server that sleeps when idle, so the first sign-in can
          take up to a minute while it wakes up.
        </p>
      </form>
    </div>
  )
}

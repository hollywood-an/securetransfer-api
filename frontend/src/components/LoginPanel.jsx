import { useState } from 'react'
import { apiRequest } from '../api.js'

// Screen 1 — Login. Sends username/password to POST /auth/login, and on success
// hands the token + role back up to <App> (which holds it in memory).
export default function LoginPanel({ baseUrl, onLogin }) {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
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
        Log in as <strong>admin</strong> or <strong>teller</strong> (staff) to open accounts,
        move money, and work the fraud-review queue.
      </p>
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
      </form>
    </div>
  )
}

import { useState } from 'react'
import { apiRequest, money } from '../api.js'

// Screen 2 — Account dashboard. Look up an account and show its balance, status,
// and ledger (the append-only transaction history, made visible). Also includes
// a one-click "seed demo data" button (staff) so a demo has accounts to play with.
export default function AccountPanel({ baseUrl, auth, onSeed }) {
  const [accountId, setAccountId] = useState('')
  const [account, setAccount] = useState(null)
  const [error, setError] = useState(null)
  const [seeding, setSeeding] = useState(false)
  const [seedMsg, setSeedMsg] = useState(null)

  async function lookup(id) {
    const target = id ?? accountId
    if (!target) return
    setError(null)
    const { ok, data } = await apiRequest(baseUrl, { path: `/accounts/${target}`, token: auth.token })
    if (ok) { setAccount(data); setAccountId(String(target)) }
    else { setAccount(null); setError(data?.message || `Could not load account ${target}`) }
  }

  // Create a customer + two USD accounts (A funded, B empty) and lift their ids
  // to <App> so the transfer form can pre-fill them.
  async function seedDemo() {
    setSeeding(true); setSeedMsg(null); setError(null)
    const stamp = Date.now()
    const cust = await apiRequest(baseUrl, {
      method: 'POST', path: '/customers', token: auth.token,
      body: { name: 'Demo Customer', email: `demo+${stamp}@example.com` },
    })
    if (!cust.ok) { setSeeding(false); setError(cust.data?.message || 'Could not create customer'); return }
    const customerId = cust.data.id
    const a = await apiRequest(baseUrl, {
      method: 'POST', path: '/accounts', token: auth.token,
      body: { customerId, currency: 'USD', initialBalance: 100000 },
    })
    const b = await apiRequest(baseUrl, {
      method: 'POST', path: '/accounts', token: auth.token,
      body: { customerId, currency: 'USD' },
    })
    setSeeding(false)
    if (!a.ok || !b.ok) { setError('Could not open the demo accounts'); return }
    const demo = { customerId, accountA: a.data.id, accountB: b.data.id }
    setSeedMsg(`Created customer #${customerId} · account A #${demo.accountA} ($100,000) · account B #${demo.accountB} ($0)`)
    onSeed(demo)
    lookup(demo.accountA)
  }

  const isStaff = auth.role === 'ADMIN' || auth.role === 'TELLER'

  return (
    <div className="card">
      <h2>Account</h2>

      {isStaff && (
        <div className="seed">
          <button className="btn btn-ghost" onClick={seedDemo} disabled={seeding}>
            {seeding ? 'Seeding…' : '＋ Seed demo (customer + 2 USD accounts)'}
          </button>
          {seedMsg && <div className="result-ok small">{seedMsg}</div>}
        </div>
      )}

      <div className="field row">
        <input
          placeholder="Account id"
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && lookup()}
        />
        <button className="btn" onClick={() => lookup()}>Look up</button>
      </div>

      {error && <div className="result-err">{error}</div>}

      {account && (
        <div className="account-view">
          <div className="balance-row">
            <div>
              <div className="muted small">Account #{account.id} · {account.currency}</div>
              <div className="balance">{money(account.balance)}</div>
            </div>
            <span className={`badge ${account.status === 'FROZEN' ? 'badge-frozen' : 'badge-active'}`}>
              {account.status}
            </span>
          </div>

          <div className="muted small ledger-title">Ledger ({account.ledger.length} entries)</div>
          {account.ledger.length === 0 ? (
            <div className="muted small">No transactions yet.</div>
          ) : (
            <table className="ledger">
              <thead>
                <tr><th>Direction</th><th>Amount</th><th>Transfer</th><th>When</th></tr>
              </thead>
              <tbody>
                {account.ledger.map((e) => (
                  <tr key={e.id}>
                    <td><span className={`tag ${e.direction === 'DEBIT' ? 'debit' : 'credit'}`}>{e.direction}</span></td>
                    <td className="num">{money(e.amount)}</td>
                    <td className="num">#{e.transferId}</td>
                    <td className="muted small">{new Date(e.createdAt).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  )
}

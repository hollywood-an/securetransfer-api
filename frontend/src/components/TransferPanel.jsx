import { useState, useEffect } from 'react'
import { apiRequest, newIdempotencyKey, money } from '../api.js'

// Screen 3 — Transfer form. Sends money and shows BOTH outcomes: the success
// state (money moved, new sender balance, FLAGGED if the fraud rules fired) and
// the rejected state (insufficient funds → 422, unknown account → 404, frozen →
// 409, etc.) so the error handling is visible.
export default function TransferPanel({ baseUrl, auth, demo, onTransferred }) {
  const [fromAccount, setFromAccount] = useState('')
  const [toAccount, setToAccount] = useState('')
  const [amount, setAmount] = useState('25000')
  const [result, setResult] = useState(null) // { ok, status, data }
  const [busy, setBusy] = useState(false)

  // Pre-fill from/to when the demo accounts are seeded.
  useEffect(() => {
    if (demo?.accountA) setFromAccount(String(demo.accountA))
    if (demo?.accountB) setToAccount(String(demo.accountB))
  }, [demo])

  async function send(e) {
    e.preventDefault()
    setBusy(true)
    setResult(null)
    const res = await apiRequest(baseUrl, {
      method: 'POST',
      path: '/transfers',
      token: auth.token,
      idempotencyKey: newIdempotencyKey(),
      body: { fromAccount: Number(fromAccount), toAccount: Number(toAccount), amount: Number(amount) },
    })
    setBusy(false)
    setResult(res)
    if (res.ok) onTransferred?.(res.data)
  }

  return (
    <div className="card">
      <h2>Transfer</h2>
      <form onSubmit={send}>
        <div className="field row">
          <div className="grow">
            <label>From account</label>
            <input value={fromAccount} onChange={(e) => setFromAccount(e.target.value)} placeholder="e.g. 1" />
          </div>
          <div className="grow">
            <label>To account</label>
            <input value={toAccount} onChange={(e) => setToAccount(e.target.value)} placeholder="e.g. 2" />
          </div>
        </div>
        <div className="field">
          <label>Amount (USD)</label>
          <input value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="25000" />
          <div className="muted small">Try ≥ $10,000 to trigger a fraud flag, or a huge amount to see a 422 rejection.</div>
        </div>
        <button className="btn btn-primary" type="submit" disabled={busy || !fromAccount || !toAccount || !amount}>
          {busy ? 'Sending…' : `Send ${money(amount)}`}
        </button>
      </form>

      {result && result.ok && (
        <div className="result-ok">
          <strong>Transfer {result.data.status}</strong> — {money(result.data.amount)} sent.
          Sender balance is now <strong>{money(result.data.fromBalance)}</strong>.
          {result.data.status === 'FLAGGED' && (
            <div className="small">Flagged for review — it still completed; check the Fraud reviews panel.</div>
          )}
        </div>
      )}
      {result && !result.ok && (
        <div className="result-err">
          <strong>Rejected ({result.status})</strong> — {result.data?.message || 'The transfer was not processed.'}
        </div>
      )}
    </div>
  )
}

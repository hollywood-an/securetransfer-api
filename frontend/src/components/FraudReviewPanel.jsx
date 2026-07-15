import { useState, useEffect, useCallback } from 'react'
import { apiRequest } from '../api.js'

// Screen 4 — Admin fraud-review queue. Lists flagged transfers with the agent's
// risk score, reasoning, and recommendation, plus APPROVE / HOLD / ESCALATE
// buttons (the human-in-the-loop step). Staff (TELLER/ADMIN) only.
export default function FraudReviewPanel({ baseUrl, auth, refreshKey }) {
  const [reviews, setReviews] = useState([])
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(false)

  const load = useCallback(async () => {
    setLoading(true); setError(null)
    const { ok, status, data } = await apiRequest(baseUrl, { path: '/fraud-reviews?size=20', token: auth.token })
    setLoading(false)
    if (ok) setReviews(data.content || [])
    else if (status === 403) setError('Fraud reviews are staff-only — sign in as admin or teller.')
    else setError(data?.message || 'Could not load reviews')
  }, [baseUrl, auth.token])

  // Load on mount, and whenever a new transfer is made (refreshKey changes).
  useEffect(() => { load() }, [load, refreshKey])

  async function decide(id, decision) {
    const { ok, data } = await apiRequest(baseUrl, {
      method: 'POST', path: `/fraud-reviews/${id}/decision`, token: auth.token, body: { decision },
    })
    if (ok) setReviews((rs) => rs.map((r) => (r.id === id ? data : r)))
    else setError(data?.message || 'Could not record decision')
  }

  return (
    <div className="card wide">
      <div className="card-head">
        <h2>Fraud reviews <span className="ai-chip">AI</span></h2>
        <button className="btn btn-ghost" onClick={load} disabled={loading}>
          {loading ? 'Refreshing…' : '↻ Refresh'}
        </button>
      </div>

      {error && <div className="result-err">{error}</div>}
      {!error && reviews.length === 0 && <div className="muted small">No flagged transfers yet. Make a large or new-payee transfer.</div>}

      <div className="reviews">
        {reviews.map((r) => (
          <div className="review" key={r.id}>
            <div className="review-top">
              <div>
                <div className="muted small">Transfer #{r.transferId} · review #{r.id}</div>
                <div className="flags">{(r.flagReasons || []).map((f) => <span className="flag" key={f}>{f}</span>)}</div>
              </div>
              <ScoreBadge score={r.riskScore} action={r.recommendedAction} />
            </div>

            {r.reasoning && <p className="reasoning">“{r.reasoning}”</p>}
            <div className="muted small">
              {r.status} · {r.agentModel || '—'}
              {r.humanDecision && <> · human decided: <strong>{r.humanDecision}</strong> by {r.decidedByUser}</>}
            </div>

            {r.status === 'AGENT_COMPLETED' ? (
              <div className="decide">
                <span className="muted small">Your call:</span>
                <button className="btn ok" onClick={() => decide(r.id, 'APPROVE')}>Approve</button>
                <button className="btn warn" onClick={() => decide(r.id, 'HOLD')}>Hold</button>
                <button className="btn danger" onClick={() => decide(r.id, 'ESCALATE')}>Escalate</button>
              </div>
            ) : r.status === 'PENDING' ? (
              <div className="muted small pending">Agent is reviewing… hit Refresh in a few seconds.</div>
            ) : null}
          </div>
        ))}
      </div>
    </div>
  )
}

function ScoreBadge({ score, action }) {
  const level = score == null ? 'unknown' : score >= 70 ? 'high' : score >= 40 ? 'med' : 'low'
  return (
    <div className={`score score-${level}`}>
      <div className="score-num">{score ?? '—'}</div>
      <div className="score-label">{action || 'risk'}</div>
    </div>
  )
}

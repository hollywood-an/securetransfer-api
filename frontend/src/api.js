// Tiny fetch wrapper for the SecureTransfer REST API.
//
// There is NO new backend logic here — this just calls the endpoints that
// already exist. The JWT lives in React state (in memory) and is passed in on
// each call, then sent as `Authorization: Bearer <token>`.

export async function apiRequest(baseUrl, { method = 'GET', path, token, body, idempotencyKey } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  // POST /transfers requires a unique Idempotency-Key so a retry can't double-charge.
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey

  let res
  try {
    res = await fetch(`${baseUrl.replace(/\/$/, '')}${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    })
  } catch (networkError) {
    // A failed fetch is usually the backend being down/asleep or a CORS block.
    return { ok: false, status: 0, data: { message: 'Network/CORS error — is the backend up and is this origin allowed by CORS?' } }
  }

  // Endpoints return JSON on success and our ApiError shape on failure; tolerate empty bodies.
  let data = null
  const text = await res.text()
  if (text) {
    try { data = JSON.parse(text) } catch { data = { message: text } }
  }
  return { ok: res.ok, status: res.status, data }
}

// A fresh idempotency key per transfer attempt.
export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return 'k-' + Date.now() + '-' + Math.random().toString(16).slice(2)
}

// Format a NUMERIC(19,4) money string/number as $1,234.56.
export function money(value) {
  const n = Number(value)
  if (Number.isNaN(n)) return String(value)
  return n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
}

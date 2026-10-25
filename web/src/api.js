const TOKEN_KEY = 'ocw_token'
const USER_KEY = 'ocw_username'

export function getToken() { return localStorage.getItem(TOKEN_KEY) || '' }
export function getStoredUsername() { return localStorage.getItem(USER_KEY) || '' }
export function setSession(token, username) {
  localStorage.setItem(TOKEN_KEY, token)
  if (username) localStorage.setItem(USER_KEY, username)
}
export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch('/api' + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })

  if (res.status === 401) {
    clearSession()
    window.location.reload()
    throw new Error('登录已过期，请重新登录')
  }

  let json = null
  try { json = await res.json() } catch { /* empty body */ }

  if (!res.ok) {
    const err = new Error((json && (json.message || json.error)) || `HTTP ${res.status}`)
    err.status = res.status
    err.data = json
    throw err
  }
  return json
}

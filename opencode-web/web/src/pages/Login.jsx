import React, { useState } from 'react'
import { api, setSession } from '../api.js'

export default function Login({ onLogin }) {
  const [mode, setMode] = useState('login') // login | register
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const res = await api(`/auth/${mode}`, { method: 'POST', body: { username, password } })
      setSession(res.token, res.username)
      onLogin(res.token)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={submit}>
        <h1>🪺 CodeNest</h1>
        <p className="hint">每个账号自动获得一个专属的 OpenCode 沙箱容器</p>

        <div className="tabs">
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>登录</button>
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>注册</button>
        </div>

        <label>用户名</label>
        <input value={username} onChange={e => setUsername(e.target.value)}
               placeholder="3-32 位字母/数字/_/-" autoFocus />

        <label>密码</label>
        <input type="password" value={password} onChange={e => setPassword(e.target.value)}
               placeholder="至少 6 位" />

        {error && <div className="error">{error}</div>}

        <button className="primary" disabled={busy || !username || !password}>
          {busy ? '请稍候…' : (mode === 'login' ? '登录' : '注册并登录')}
        </button>
      </form>
    </div>
  )
}

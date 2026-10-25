import React, { Component, useState } from 'react'
import Login from './pages/Login.jsx'
import Chat from './pages/Chat.jsx'
import Settings from './pages/Settings.jsx'
import { getToken, clearSession, getStoredUsername } from './api.js'

class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }
  static getDerivedStateFromError(error) {
    return { error }
  }
  render() {
    if (this.state.error) {
      return (
        <div className="center" style={{ height: '80vh' }}>
          <p className="error">页面出错：{String(this.state.error?.message || this.state.error)}</p>
          <button className="primary" onClick={() => { this.setState({ error: null }); this.props.onReset?.() }}>
            重新加载
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

export default function App() {
  const [token, setToken] = useState(getToken())
  const [page, setPage] = useState('chat')
  const [username] = useState(getStoredUsername())

  if (!token) {
    return <Login onLogin={setToken} />
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">🪺</span> CodeNest
        </div>
        <nav className="nav">
          <button className={page === 'chat' ? 'active' : ''} onClick={() => setPage('chat')}>对话</button>
          <button className={page === 'settings' ? 'active' : ''} onClick={() => setPage('settings')}>模型设置</button>
        </nav>
        <div className="topbar-right">
          <span className="username">{username}</span>
          <button className="link" onClick={() => { clearSession(); setToken('') }}>退出</button>
        </div>
      </header>
      <main className="content">
        <ErrorBoundary onReset={() => setPage('chat')}>
          {page === 'chat'
            ? <Chat key="chat" />
            : <Settings key="settings" onDone={() => setPage('chat')} />}
        </ErrorBoundary>
      </main>
    </div>
  )
}

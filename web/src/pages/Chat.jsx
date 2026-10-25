import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

/** Accept plain id, sessionID, ID, or a {data:{...}} wrapper. */
function sessionId(s) {
  const o = (s && typeof s === 'object') ? s : {}
  if (o.data && typeof o.data === 'object') o.__wrapped = true
  return o.id || o.sessionID || o.ID || (o.data && (o.data.id || o.data.sessionID)) || ''
}

function unwrapArray(v) {
  if (Array.isArray(v)) return v
  if (v && Array.isArray(v.data)) return v.data
  return []
}

/**
 * Chat view. Lifecycle:
 *  1. load container status; if not running -> POST /oc/start (spinner)
 *  2. list sandbox sessions, create one if none
 *  3. poll messages + active sessions while a prompt is in flight
 */
export default function Chat() {
  const [phase, setPhase] = useState('starting') // starting | ready | error
  const [errorMsg, setErrorMsg] = useState('')
  const [sessions, setSessions] = useState([])
  const [currentId, setCurrentId] = useState('')
  const [messages, setMessages] = useState([])
  const [busy, setBusy] = useState(false)
  const [input, setInput] = useState('')
  const listRef = useRef(null)
  const busyRef = useRef(false)

  // 1. start sandbox on entry
  useEffect(() => {
    let alive = true
    setPhase('starting')
    api('/oc/status')
      .then(async (st) => {
        if (!st.running || !st.ready) {
          await api('/oc/start', { method: 'POST' })
        }
        if (alive) setPhase('ready')
      })
      .catch(err => { if (alive) { setPhase('error'); setErrorMsg(err.message) } })
    return () => { alive = false }
  }, [])

  // 2. when ready: load sessions
  useEffect(() => {
    if (phase !== 'ready') return
    let alive = true
    ;(async () => {
      try {
        let list = unwrapArray(await api('/oc/session'))
        if (list.length === 0) {
          const created = await api('/oc/session', { method: 'POST', body: {} })
          if (created) list = [created]
        }
        if (!alive) return
        setSessions(list)
        const first = list.find(s => sessionId(s))
        if (first) setCurrentId(sessionId(first))
      } catch (err) {
        if (alive) { setPhase('error'); setErrorMsg(err.message) }
      }
    })()
    return () => { alive = false }
  }, [phase])

  // 3. poll messages + active (busy) state
  useEffect(() => {
    if (phase !== 'ready' || !currentId) return
    let alive = true

    async function poll() {
      if (!alive) return
      try {
        const msgs = unwrapArray(await api(`/oc/session/${currentId}/message`))
        if (alive) setMessages(Array.isArray(msgs) ? msgs : [])

        const active = await api('/oc/session/active')
        const isActive = JSON.stringify(active).includes(currentId)
        setBusy(isActive)
        busyRef.current = isActive
      } catch { /* transient */ }
    }

    poll()
    const timer = setInterval(poll, 1500)
    return () => { alive = false; clearInterval(timer) }
  }, [phase, currentId])

  // autoscroll
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight
  }, [messages])

  async function send() {
    const text = input.trim()
    if (!text || busyRef.current) return
    setInput('')
    try {
      await api(`/oc/session/${currentId}/prompt`, { method: 'POST', body: { text } })
      setBusy(true)
      busyRef.current = true
    } catch (err) {
      setErrorMsg(err.message)
    }
  }

  function renderParts(msg, idx) {
    // V2 schema: {type:"user", text} | {type:"assistant", content:[{type:"text"|"reasoning"|"tool"}]}
    // legacy shapes (role/parts/info) kept as fallbacks
    const type = msg.type || msg.info?.type || msg.role
    if (type === 'idle') {
      return <div key={'idle' + idx} className="done-line">✓ 本轮已完成</div>
    }
    const isUser = type === 'user'
    const content = msg.content || msg.info?.content || msg.parts || []
    const textItems = content.filter(p => (p.type === 'text' || p.type === 'reasoning') && p.text)
    const toolItems = content.filter(p => p.type === 'tool')
    const userText = msg.text || msg.info?.text

    return (
      <div key={msg.id || idx} className={`bubble ${isUser ? 'me' : 'assistant'}`}>
        <div className="role">{isUser ? '你' : 'OpenCode'}</div>
        {toolItems.map((p, i) => (
          <div key={i} className="tool-line">🔧 {p.tool || p.name} — {p.state?.status || p.status || 'done'}</div>
        ))}
        {isUser && userText && <pre className="text">{userText}</pre>}
        {!isUser && reasoningHint(content)}
        {!isUser && textItems.filter(p => p.type === 'text').map((p, i) =>
          <pre key={i} className="text">{p.text}</pre>)}
      </div>
    )
  }

  /** reasoning shown collapsed as a faint line */
  function reasoningHint(content) {
    const r = content.find(p => p.type === 'reasoning' && p.text)
    if (!r) return null
    return <div className="tool-line">💭 {r.text.slice(-80).replace(/\n/g, ' ')}</div>
  }

  if (phase === 'starting') {
    return (
      <div className="center">
        <div className="spinner" />
        <p>正在创建/启动你的专属 OpenCode 容器（首次需拉取镜像，可能需要几分钟）…</p>
      </div>
    )
  }
  if (phase === 'error') {
    return (
      <div className="center">
        <p className="error">{errorMsg}</p>
        <button className="primary" onClick={() => window.location.reload()}>重试</button>
      </div>
    )
  }

  return (
    <div className="chat-layout">
      <aside className="sidebar">
        <button className="primary block" onClick={async () => {
          try {
            const created = await api('/oc/session', { method: 'POST', body: {} })
            setSessions(s => [created, ...s])
            const id = sessionId(created)
            if (id) { setCurrentId(id); setMessages([]) }
          } catch (err) { setErrorMsg(err.message) }
        }}>＋ 新会话</button>
        <div className="session-list">
          {sessions.map(s => {
            const id = sessionId(s)
            if (!id) return null
            return (
              <button key={id}
                      className={'session-item' + (id === currentId ? ' active' : '')}
                      onClick={() => { setCurrentId(id); setMessages([]) }}>
                {s.title || s.name || id.slice(0, 12)}
              </button>
            )
          })}
        </div>
        <div className="sidebar-footer">
          <span className="dot running" /> 容器运行中
        </div>
      </aside>

      <section className="chat-main">
        <div className="message-list" ref={listRef}>
          {messages.length === 0 && <p className="hint">向 OpenCode 提问，它会在你的专属容器中执行任务。</p>}
          {messages.map(renderParts)}
          {busy && <div className="busy">⏳ OpenCode 正在运行工具…</div>}
        </div>
        <div className="composer">
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
            }}
            placeholder={busy ? '任务运行中…' : '输入消息，Enter 发送 / Shift+Enter 换行'}
            disabled={busy}
          />
          <button className="primary" onClick={send} disabled={busy || !input.trim()}>
            发送
          </button>
          {busy && <button className="secondary" onClick={() =>
            api(`/oc/session/${currentId}/interrupt`, { method: 'POST' }).catch(() => {})
          }>中断</button>}
        </div>
      </section>
    </div>
  )
}

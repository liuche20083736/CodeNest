import React, { useEffect, useRef, useState } from 'react'
import { api } from '../api.js'

export default function Settings({ onDone }) {
  const [form, setForm] = useState({ providerId: '', npmModule: '', modelId: '', baseUrl: '', hasApiKey: false })
  const [apiKey, setApiKey] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [models, setModels] = useState([])
  const [loadingModels, setLoadingModels] = useState(false)

  useEffect(() => {
    api('/oc/settings').then(s => {
      setForm({ ...s, apiKey: '' })
    }).catch(err => setError(err.message))
  }, [])

  function update(k, v) { setForm(f => ({ ...f, [k]: v })) }

  async function save() {
    setError(''); setMessage('')
    try {
      const res = await api('/oc/settings', {
        method: 'PUT',
        body: {
          providerId: form.providerId,
          npmModule: form.npmModule,
          modelId: form.modelId,
          baseUrl: form.baseUrl,
          apiKey, // empty = keep existing
        },
      })
      setMessage(res.appliedToContainer
        ? '已保存并写入容器配置'
        : '已保存（容器下次启动时生效，或点击对话页重启动容器）')
      setApiKey('')
    } catch (err) { setError(err.message) }
  }

  async function loadModels() {
    setError(''); setMessage('')
    setLoadingModels(true)
    try {
      const res = await api('/oc/models')
      const list = []
      const walk = node => {
        if (!node) return
        if (Array.isArray(node)) return node.forEach(walk)
        if (typeof node === 'object') {
          const id = node.id || node.ID
          if (typeof id === 'string') list.push(id)
          Object.values(node).forEach(walk)
        }
      }
      walk(res.models || res)
      setModels([...new Set(list)].slice(0, 200))
    } catch (err) {
      setError(err.message)
    } finally {
      setLoadingModels(false)
    }
  }

  return (
    <div className="settings">
      <h2>模型设置 <span className="hint-inline">写入你的专属容器内的 opencode 全局配置</span></h2>

      <label>Provider ID</label>
      <input value={form.providerId} onChange={e => update('providerId', e.target.value)}
             placeholder="anthropic / openai / google / ..." />

      <label>默认模型（providerID/modelID）</label>
      <input value={form.modelId} onChange={e => update('modelId', e.target.value)}
             placeholder="例如 anthropic/claude-sonnet-4-5" />

      {models.length > 0 && (
        <select value="" onChange={e => e.target.value && update('modelId', e.target.value)}>
          <option value="" disabled>从容器内模型列表选择…</option>
          {models.map(m => <option key={m} value={m}>{m}</option>)}
        </select>
      )}
      <button className="link" onClick={loadModels} disabled={loadingModels}>
        {loadingModels ? '加载中…' : (models.length > 0 ? '为容器刷新模型列表 ↻' : '从容器获取模型列表 ↻')}
      </button>

      <label>API Key</label>
      <input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)}
             placeholder={form.hasApiKey ? '已配置（输入新值可覆盖）' : '必填（仅对你自己的容器可见）'} />

      <label>自定义 Base URL（可选，openai-compatible）</label>
      <input value={form.baseUrl} onChange={e => update('baseUrl', e.target.value)}
             placeholder="https://api.example.com/v1" />

      <label>自定义 Provider npm 包（可选）</label>
      <input value={form.npmModule} onChange={e => update('npmModule', e.target.value)}
             placeholder="@ai-sdk/openai-compatible" />

      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}

      <div className="row">
        <button className="primary" onClick={save}>保存</button>
        <button className="secondary" onClick={onDone}>返回对话</button>
      </div>
    </div>
  )
}

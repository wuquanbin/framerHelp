/**
 * 管理后台 WebSocket 单例封装（原生 WS 协议，非 SockJS / STOMP）
 *
 * 连接地址：{httpBase}/ws/chat?role=ADMIN&token=JWT
 * 暴露 on(type, handler) / send(type, body) / 关闭 等简单接口，
 * 与后端 ChatWebSocketHandler 的 WsMessage 协议一致。
 */

import type { WsMessage } from '@/types/weather'
import { BASE_URL } from '@/api/admin'

type Handler = (payload: WsMessage<any>) => void

// 从 axios 的 API 地址派生 WS 地址，保持单一配置来源。
// 注意：生产环境 BASE_URL 是相对路径 /api（同源部署），此时必须用当前页面地址补全，
// 否则会拼出 "ws:///ws/chat" 这种非法地址，人工专家聊天会连不上。
const WS_HTTP_BASE = (() => {
  const derived = BASE_URL.replace(/\/api$/, '')
  return derived || window.location.origin
})()

function wsUrlOf(httpBase: string, token: string) {
  const proto = httpBase.startsWith('https://') ? 'wss://' : 'ws://'
  const host = httpBase.replace(/^https?:\/\//, '')
  return `${proto}${host}/ws/chat?role=ADMIN&token=${encodeURIComponent(token)}`
}

class ChatWsService {
  private _ws: WebSocket | null = null
  private _connected = false
  private _handlers = new Map<string, Set<Handler>>()
  private _queue: Array<{ type: string; body: unknown }> = []
  private _reconnectTimer: number | null = null
  private _manualClose = false

  on(type: string, fn: Handler) {
    if (!this._handlers.has(type)) this._handlers.set(type, new Set())
    this._handlers.get(type)!.add(fn)
    return () => this._handlers.get(type)?.delete(fn)
  }

  off(type: string, fn: Handler) {
    this._handlers.get(type)?.delete(fn)
  }

  private emit(msg: WsMessage<unknown>) {
    this._handlers.get(msg.type)?.forEach(h => h(msg))
    // 同时给订阅"*"的回调
    this._handlers.get('*')?.forEach(h => h(msg))
  }

  get connected() { return this._connected }

  connect() {
    if (this._ws && this._connected) return
    const token = localStorage.getItem('admin_token') || ''
    if (!token) {
      console.warn('[AdminWS] 缺少 admin_token，暂不连接')
      return
    }
    this._manualClose = false
    try {
      this._ws = new WebSocket(wsUrlOf(WS_HTTP_BASE, token))
    } catch (e) {
      console.error('[AdminWS] 创建连接失败', e)
      this.scheduleReconnect()
      return
    }
    this._ws.onopen = () => {
      this._connected = true
      this.emit({ type: '__OPEN__', timestamp: Date.now() })
      // flush queue
      while (this._queue.length) {
        const m = this._queue.shift()!
        this.send(m.type, m.body)
      }
    }
    this._ws.onclose = () => {
      this._connected = false
      this.emit({ type: '__CLOSE__', timestamp: Date.now() })
      this.scheduleReconnect()
    }
    this._ws.onerror = (ev) => {
      console.error('[AdminWS] 连接错误', ev)
      this.emit({ type: '__ERROR__', timestamp: Date.now(), body: ev })
    }
    this._ws.onmessage = (ev) => {
      try {
        const msg: WsMessage<unknown> = JSON.parse(ev.data)
        this.emit(msg)
      } catch (e) {
        console.warn('[AdminWS] 解析消息失败', ev.data, e)
      }
    }
  }

  send(type: string, body?: unknown) {
    const payload = JSON.stringify({ type, body: body ?? null, timestamp: Date.now() })
    if (this._connected && this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(payload)
    } else {
      this._queue.push({ type, body })
      if (!this._ws) this.connect()
    }
  }

  private scheduleReconnect() {
    if (this._manualClose) return
    if (this._reconnectTimer != null) return
    this._reconnectTimer = window.setTimeout(() => {
      this._reconnectTimer = null
      this.connect()
    }, 3000)
  }

  close() {
    this._manualClose = true
    if (this._reconnectTimer != null) {
      clearTimeout(this._reconnectTimer)
      this._reconnectTimer = null
    }
    try { this._ws?.close() } catch (e) { /* ignore */ }
    this._ws = null
    this._connected = false
  }
}

export const adminChatWs = new ChatWsService()

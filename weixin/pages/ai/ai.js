const app = getApp()
const { post, request, BASE_URL } = require('../../utils/request')
const { markdownToNodes } = require('../../utils/markdown')
const { streamChat } = require('../../utils/ai-stream')

// ---------------- WebSocket 协议 ----------------
//
// 客户端 → 服务端：
//   FARMER_REQUEST { aiContext }  请求转人工
//   CHAT { sessionId, content }   发送聊天消息
//   READ { sessionId }            标记已读
//   CLOSE { sessionId }           关闭会话
//
// 服务端 → 客户端：
//   CONNECTED { role, identity, nickname, villageCode, villageName }
//   SESSION_CREATED  { session, onlineExperts }
//   SESSION_ACCEPTED { session, adminInfo }
//   NO_EXPERT_ONLINE { session, onlineExperts, message }
//   NEW_CHAT { sessionId, message }
//   SESSION_CLOSED { sessionId }
//   ERROR { message }
// ------------------------------------------------

// BASE_URL -> WS URL
function wsUrlOf(baseUrl, params) {
  const proto = baseUrl.startsWith('https://') ? 'wss://' : 'ws://'
  const host = baseUrl.replace(/^https?:\/\//, '')
  const qs = Object.keys(params)
    .filter(k => params[k] != null && params[k] !== '')
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k]))
    .join('&')
  return proto + host + '/ws/chat?' + qs
}

// 人工消息转"节点"（仅简单文本，不需要 markdown 解析）
function textToNodes(text) {
  return [
    { name: 'div', attrs: { class: 'plain-text' }, children: [{ type: 'text', text: text }] }
  ]
}

// 聊天气泡时间显示：今天→HH:mm；今年跨天→MM-DD HH:mm；跨年→YYYY-MM-DD HH:mm
function fmtChatTime(raw) {
  const two = n => (n < 10 ? '0' + n : '' + n)
  const fmt = dt => {
    if (!dt || isNaN(dt.getTime())) return ''
    const hm = two(dt.getHours()) + ':' + two(dt.getMinutes())
    const now = new Date()
    const sameDay = dt.getFullYear() === now.getFullYear() &&
      dt.getMonth() === now.getMonth() && dt.getDate() === now.getDate()
    if (sameDay) return hm
    const md = two(dt.getMonth() + 1) + '-' + two(dt.getDate())
    if (dt.getFullYear() === now.getFullYear()) return md + ' ' + hm
    return dt.getFullYear() + '-' + md + ' ' + hm
  }
  if (!raw) return ''
  if (raw instanceof Date) return fmt(raw)
  if (typeof raw !== 'string') return ''
  // 后端 createdAt 形如 "2026-09-02T10:42:00.123456"，iOS 不支持直接解析，手动拆分
  const p = raw.replace('T', ' ').split('.')[0].split(/[- :]/)
  if (p.length < 3) return ''
  return fmt(new Date(+p[0], +p[1] - 1, +p[2], +p[3] || 0, +p[4] || 0, +p[5] || 0))
}

// 分钟分组 key（精确到分，不考虑秒）：同一分钟内的消息只在第一条显示时间
function minuteKeyOf(raw) {
  const two = n => (n < 10 ? '0' + n : '' + n)
  if (raw instanceof Date) {
    return raw.getFullYear() + '-' + two(raw.getMonth() + 1) + '-' + two(raw.getDate()) +
      ' ' + two(raw.getHours()) + ':' + two(raw.getMinutes())
  }
  if (typeof raw !== 'string') return ''
  return raw.replace('T', ' ').split('.')[0].slice(0, 16)
}

Page({
  data: {
    // -------- 通用消息区 --------
    messages: [],       // 通用消息：{id, role: user|ai|human, content, contentNodes?, senderName?, isSystem?}
    inputText: '',
    isLoading: false,   // AI 正在生成（生成期间禁止重复发送；思考动画由空气泡自己显示）
    scrollToId: 'msg-welcome',
    msgCounter: 0,
    userInitial: '我',

    // -------- 模式 --------
    chatMode: 'AI',     // AI | HUMAN
    humanStatus: 'NONE', // NONE（未开会话）/ REQUESTING（已请求，等待专家）/ WAITING（等待接单）/ ACTIVE（专家在聊）/ CLOSED

    // -------- 人工会话 --------
    sessionId: null,
    adminInfo: null,    // { username, realName }
    currentSession: null,

    // -------- UI --------
    showTransferTip: true,  // 未请求过人工时，底部显示"转人工客服"卡片
    expertOnlineCount: 0,
  },

  ws: null,        // socket task
  wsOpen: false,
  wsManualClose: false,
  wsReconnectTimer: null,

  // ============================================================
  onLoad() {
    const userInfo = wx.getStorageSync('userInfo') || {}
    const nickName = userInfo.nickName || '我'
    const initial = nickName.charAt(0)
    this.setData({
      villageCode: app.globalData.villageCode || 'baini',
      villageName: app.globalData.villageName || '白坭镇',
      openid: wx.getStorageSync('openid') || '',
      nickname: userInfo.nickName || '农户',
      userInitial: initial,
    })
    this.queryExpertOnline()
    this.recoverSession()
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 2 })
    }
    // tab 页 onLoad 只执行一次：每次回到本页需同步最新登录态
    // （用户可能切到"我的"页完成登录，storage 里此时才有 openid）
    const userInfo = wx.getStorageSync('userInfo') || {}
    const openid = wx.getStorageSync('openid') || ''
    if (openid !== this.data.openid) {
      this.setData({
        openid,
        nickname: userInfo.nickName || '农户',
        userInitial: (userInfo.nickName || '我').charAt(0),
      })
      // 登录态刚建立/变化：刷新专家在线数，并尝试恢复进行中的人工会话
      if (openid) {
        this.queryExpertOnline()
        this.recoverSession()
      }
    } else if (userInfo.nickName && userInfo.nickName !== this.data.nickname) {
      // 仅昵称更新（如个人页改了昵称）
      this.setData({ nickname: userInfo.nickName, userInitial: userInfo.nickName.charAt(0) })
    }
  },

  onUnload() {
    this.closeWs()
    // 页面销毁后不能再往 setData 里塞增量，先断开流式连接
    if (this.aiStream) this.aiStream.abort()
  },
  onHide() {
    // 切后台可以不断线，保持消息接收
  },

  // ============== 恢复未关闭会话 ==============
  async recoverSession() {
    const openid = this.data.openid
    if (!openid) return
    try {
      const res = await request({
        url: '/api/human-chat/my-active-session',
        method: 'GET',
        data: { openid },
      })
      const s = res && res.data ? res.data.session : null
      const msgs = res && res.data ? res.data.messages : []
      if (s) {
        this.data.sessionId = s.id
        this.data.currentSession = s
        const upd = { chatMode: 'HUMAN', sessionId: s.id, currentSession: s, showTransferTip: false }
        if (s.status === 'WAITING') {
          upd.humanStatus = 'WAITING'
        } else if (s.status === 'ACTIVE') {
          upd.humanStatus = 'ACTIVE'
        }
        // 如果没有已存在的消息，把历史消息追加到 messages
        const existingHuman = this.data.messages.filter(m => m.role === 'human').length
        if (!existingHuman) {
          const newMessages = this.appendHistoryToMessages(msgs)
          if (newMessages.length > 0) {
            upd.messages = [...this.data.messages, ...newMessages]
          }
        }
        this.setData(upd, () => this.scrollToBottom())
        // 自动建立 WS 连接
        this.ensureWs()
      }
    } catch (e) {
      // 忽略恢复失败
    }
  },

  queryExpertOnline() {
    request({ url: '/api/human-chat/online-experts', method: 'GET' })
      .then(res => {
        if (res && res.data && typeof res.data.onlineCount === 'number') {
          this.setData({ expertOnlineCount: res.data.onlineCount })
        }
      })
      .catch((e) => {
        // 在线专家数是装饰性信息，失败不打断对话；但要留日志，
        // 否则真机上「专家永远显示离线」会无从查起
        console.warn('[ai] 在线专家数查询失败', (e && e.errMsg) || (e && e.data && e.data.message) || e)
      })
  },

  // ============== 输入 / 快捷提问 ==============
  onInput(e) {
    this.setData({ inputText: e.detail.value })
  },
  askQuick(e) {
    const q = e.currentTarget.dataset.q
    this.setData({ inputText: q })
    this.sendMessage()
  },

  // ============== 统一发送入口 ==============
  sendMessage() {
    const text = (this.data.inputText || '').trim()
    if (!text) return
    if (this.data.chatMode === 'HUMAN' && (this.data.humanStatus === 'ACTIVE' || this.data.humanStatus === 'WAITING')) {
      this.sendHumanChat(text)
    } else {
      this.sendAiChat(text)
    }
  },

  // ============== AI 模式发送（流式逐字输出） ==============
  sendAiChat(text) {
    if (this.data.isLoading) return

    const history = this.data.messages
      .filter(m => m.role === 'user' || m.role === 'ai')
      .slice(-6)
      .map(m => ({
        role: m.role === 'user' ? 'user' : 'assistant',
        content: m.content,
      }))

    const payload = {
      message: text,
      villageCode: this.data.villageCode,
      history: history,
    }

    const userMsgId = this.data.msgCounter
    const aiMsgId = userMsgId + 1
    const mk = minuteKeyOf(new Date())
    const prevMsg = this.data.messages[this.data.messages.length - 1]
    this.setData({
      msgCounter: aiMsgId + 1,
      inputText: '',
      isLoading: true,
      messages: [...this.data.messages, {
        id: userMsgId,
        role: 'user',
        content: text,
        time: fmtChatTime(mk),
        showTime: mk !== ((prevMsg && prevMsg._mk) || ''),
        _mk: mk,
      }, {
        // 先插一条空气泡占位：内容为空时模板显示"思考中"的三个点，
        // 第一个字到了就原地换成正文，不再让用户盯着整块空白
        id: aiMsgId,
        role: 'ai',
        content: '',
        contentNodes: [],
        streaming: true,
      }],
    })
    this.scrollToBottom()

    // 模型吐字很碎（一个 token 一片），每片都 setData 会把渲染线程打满，
    // 攒 80ms 刷一次，肉眼仍然是逐字效果
    let buffered = ''
    let timer = null
    const flush = () => {
      timer = null
      const piece = buffered
      buffered = ''
      if (piece) this.appendAiDelta(aiMsgId, piece)
    }
    const onPiece = (delta) => {
      buffered += delta
      if (timer) return
      timer = setTimeout(flush, 80)
    }
    const settle = () => {
      if (timer) { clearTimeout(timer); timer = null }
      flush()
    }

    this.aiStream = streamChat(payload, {
      onDelta: onPiece,
      onDone: () => {
        settle()
        this.finishAiMessage(aiMsgId)
      },
      onError: (err, partial) => {
        settle()
        if (partial) {
          // 已经吐了一部分就收下，绝不重发——重发会再烧一次调用，还可能让内容翻倍
          this.finishAiMessage(aiMsgId)
          return
        }
        // 一个字都没有：说明流式这条路不通（基础库不支持分块），退回一次性接口
        console.warn('[ai] 流式无输出，回退一次性接口', (err && err.errMsg) || '')
        this.fallbackAiChat(aiMsgId, payload)
      },
    })
  },

  /** 往指定 AI 气泡尾部追加一段文字 */
  appendAiDelta(id, piece) {
    const list = this.data.messages
    const idx = list.findIndex(m => m.id === id)
    if (idx < 0) return
    const content = (list[idx].content || '') + piece
    const next = list.slice()
    next[idx] = Object.assign({}, list[idx], {
      content,
      contentNodes: markdownToNodes(content),
    })
    this.setData({ messages: next })
    this.scrollToBottom()
  },

  /** 流式收尾：去掉 streaming 标记；一个字都没收到时补一句兜底，不留空气泡 */
  finishAiMessage(id, forcedText) {
    const list = this.data.messages
    const idx = list.findIndex(m => m.id === id)
    const patch = { isLoading: false }
    if (idx >= 0) {
      const content = forcedText != null
        ? forcedText
        : ((list[idx].content || '').trim() || '抱歉，我暂时无法回答，请稍后再试。')
      const next = list.slice()
      next[idx] = Object.assign({}, list[idx], {
        content,
        contentNodes: markdownToNodes(content),
        streaming: false,
      })
      patch.messages = next
    }
    this.setData(patch)
    this.scrollToBottom()
  },

  /** 流式完全不通时的退路：老老实实调一次性接口，整段显示 */
  fallbackAiChat(id, payload) {
    post('/api/ai/chat', payload)
      .then(res => {
        const reply = res && res.data ? res.data.reply : ''
        this.finishAiMessage(id, reply || '抱歉，我暂时无法回答，请稍后再试。')
      })
      .catch(() => {
        this.finishAiMessage(id, '网络异常，请稍后再试。')
      })
  },

  // ============== 模式切换按钮 ==============
  switchToAI() {
    this.setData({ chatMode: 'AI' })
  },
  switchToHuman() {
    this.setData({ chatMode: 'HUMAN' })
    if (!this.data.sessionId) {
      // 尚未请求，直接请求转人工
      this.requestHuman()
    } else {
      this.ensureWs()
    }
  },

  // ============== 请求转人工 ==============
  requestHuman() {
    if (this.data.humanStatus === 'REQUESTING' || this.data.humanStatus === 'WAITING' || this.data.humanStatus === 'ACTIVE') {
      return
    }
    const openid = this.data.openid
    if (!openid) {
      wx.showToast({ title: '请先登录后使用人工客服', icon: 'none' })
      return
    }
    this.setData({ humanStatus: 'REQUESTING', showTransferTip: false })

    // 把 AI/用户历史转换为上下文快照
    const context = this.buildAiContextSnapshot()

    this.ensureWs(() => {
      this.sendWs('FARMER_REQUEST', { aiContext: context })
    })
  },

  buildAiContextSnapshot() {
    const items = this.data.messages
      .filter(m => m.role === 'user' || m.role === 'ai')
      .slice(-10)
      .map(m => `[${m.role === 'user' ? '农民' : 'AI助手'}] ${m.content}`)
    const header = `农户：${this.data.nickname || '农户'}，村庄：${this.data.villageName || this.data.villageCode || ''}`
    return [header, ...items].join('\n')
  },

  appendHistoryToMessages(msgs) {
    if (!msgs || !msgs.length) return []
    const result = []
    const c = this.data.msgCounter
    let prevKey = ''
    msgs.forEach((m, idx) => {
      const id = c + idx + 1
      const mk = minuteKeyOf(m.createdAt)
      const showTime = !!mk && mk !== prevKey
      prevKey = mk || prevKey
      if (m.senderType === 'FARMER') {
        result.push({ id, role: 'user', content: m.content, time: fmtChatTime(m.createdAt), showTime, _mk: mk })
      } else {
        result.push({
          id,
          role: 'human',
          content: m.content,
          senderName: m.senderName || '农技专家',
          time: fmtChatTime(m.createdAt),
          showTime,
          _mk: mk,
          contentNodes: textToNodes(m.content),
        })
      }
    })
    this.data.msgCounter = c + msgs.length
    return result
  },

  // ============== 人工聊天发送 ==============
  sendHumanChat(text) {
    if (!this.wsOpen || !this.data.sessionId) {
      wx.showToast({ title: '连接中，请稍后再试', icon: 'none' })
      // 自动触发重连+尝试发送
      this.ensureWs(() => {
        if (this.data.sessionId) this.sendWs('CHAT', { sessionId: this.data.sessionId, content: text })
      })
      return
    }
    const msgId = this.data.msgCounter
    const mk = minuteKeyOf(new Date())
    const prevMsg = this.data.messages[this.data.messages.length - 1]
    this.setData({
      msgCounter: msgId + 1,
      inputText: '',
      messages: [...this.data.messages, {
        id: msgId, role: 'user', content: text, time: fmtChatTime(mk),
        showTime: mk !== ((prevMsg && prevMsg._mk) || ''), _mk: mk,
      }],
    })
    this.scrollToBottom()
    this.sendWs('CHAT', { sessionId: this.data.sessionId, content: text })
  },

  // ============== WebSocket 控制 ==============
  ensureWs(afterOpen) {
    if (this.ws && this.wsOpen) {
      afterOpen && afterOpen()
      return
    }
    // 连接参数
    const params = {
      role: 'FARMER',
      openid: this.data.openid,
      nickname: this.data.nickname || '农户',
      villageCode: this.data.villageCode || 'baini',
      villageName: this.data.villageName || '白坭镇',
    }
    const url = wsUrlOf(BASE_URL, params)

    this.wsManualClose = false
    try {
      this.ws = wx.connectSocket({
        url: url,
        protocols: [],
        fail: (e) => {
          console.error('[WS] 连接调用失败', e)
          wx.showToast({ title: '连接人工客服失败', icon: 'none' })
          this.setData({ humanStatus: 'NONE' })
        },
      })
    } catch (e) {
      console.error('[WS] 创建 socket 异常', e)
      return
    }

    this.ws.onOpen(() => {
      this.wsOpen = true
      console.log('[WS] 农民端连接成功')
      // 若有打开中的会话，发送 READ 标记已读
      if (this.data.sessionId) {
        this.sendWs('READ', { sessionId: this.data.sessionId })
      }
      if (afterOpen) afterOpen()
    })

    this.ws.onClose(() => {
      this.wsOpen = false
      console.log('[WS] 断线')
      // 请求转人工阶段连接失败：状态回退，允许用户重新点击（避免卡死在"连接中"）
      if (this.data.humanStatus === 'REQUESTING') {
        this.setData({ humanStatus: 'NONE' })
        this.pushSystemMessage('连接客服服务器失败，请重新点击"转人工客服"再试一次。')
        return
      }
      this.scheduleReconnect()
    })

    this.ws.onError((e) => {
      console.error('[WS] 错误', e)
    })

    this.ws.onMessage((e) => {
      try {
        const msg = JSON.parse(e.data)
        this.handleWsMessage(msg)
      } catch (err) {
        console.warn('[WS] 消息解析失败', e.data)
      }
    })
  },

  scheduleReconnect() {
    if (this.wsManualClose) return
    if (this.wsReconnectTimer) return
    this.wsReconnectTimer = setTimeout(() => {
      this.wsReconnectTimer = null
      // 只在有人工会话进行中时自动重连
      if (this.data.sessionId && (this.data.humanStatus === 'ACTIVE' || this.data.humanStatus === 'WAITING')) {
        this.ensureWs()
      }
    }, 3000)
  },

  closeWs() {
    this.wsManualClose = true
    if (this.wsReconnectTimer) {
      clearTimeout(this.wsReconnectTimer)
      this.wsReconnectTimer = null
    }
    try {
      if (this.ws) this.ws.close({})
    } catch (e) { /* ignore */ }
    this.ws = null
    this.wsOpen = false
  },

  sendWs(type, body) {
    if (!this.ws) return
    const payload = JSON.stringify({ type, body: body || null, timestamp: Date.now() })
    if (this.wsOpen) {
      this.ws.send({ data: payload })
    } else {
      // 未打开，等打开后发送的逻辑由 ensureWs 的 callback 保证
      console.warn('[WS] 连接尚未建立，暂未发送:', type)
    }
  },

  // ============== WS 消息分发 ==============
  handleWsMessage(msg) {
    const type = msg.type
    const b = msg.body
    switch (type) {
      case 'CONNECTED':
        break
      case 'SESSION_CREATED': {
        const s = b.session || null
        if (s) {
          this.data.sessionId = s.id
          this.data.currentSession = s
          this.setData({
            sessionId: s.id,
            currentSession: s,
            humanStatus: 'WAITING',
            expertOnlineCount: b.onlineExperts || 0,
          })
          this.pushSystemMessage('已为您发起人工客服请求，正在等待专家接待...')
          if ((b.onlineExperts || 0) === 0) {
            this.pushSystemMessage('提示：当前无专家在线，您可以先留言或稍后刷新。')
          }
        }
        break
      }
      case 'NO_EXPERT_ONLINE': {
        const s = b.session
        if (s) {
          this.data.sessionId = s.id
          this.data.currentSession = s
          this.setData({
            sessionId: s.id,
            currentSession: s,
            humanStatus: 'WAITING',
          })
          this.pushSystemMessage('已记录您的请求。当前暂无在线专家，您可以先留言；专家上线后会看到。')
        }
        break
      }
      case 'SESSION_ACCEPTED': {
        const s = b.session
        const adminInfo = b.adminInfo || null
        if (s) {
          this.data.sessionId = s.id
          this.data.currentSession = s
        }
        const upd = { humanStatus: 'ACTIVE' }
        if (adminInfo) upd.adminInfo = adminInfo
        if (s) { upd.currentSession = s; upd.sessionId = s.id }
        this.setData(upd)
        const name = (adminInfo && adminInfo.realName) || '农技专家'
        this.pushSystemMessage(`专家「${name}」已加入会话，开始为您答疑！`)
        break
      }
      case 'NEW_CHAT': {
        const m = b.message || null
        if (!m) return
        if (m.senderType === 'ADMIN') {
          const msgId = this.data.msgCounter
          const mk = minuteKeyOf(m.createdAt)
          const prev = this.data.messages[this.data.messages.length - 1]
          this.setData({
            msgCounter: msgId + 1,
            messages: [...this.data.messages, {
              id: msgId,
              role: 'human',
              content: m.content,
              senderName: m.senderName || '农技专家',
              time: fmtChatTime(m.createdAt),
              showTime: !!mk && mk !== ((prev && prev._mk) || ''),
              _mk: mk,
              contentNodes: textToNodes(m.content),
            }],
          })
        }
        this.scrollToBottom()
        // 已读回执
        if (this.data.sessionId) this.sendWs('READ', { sessionId: this.data.sessionId })
        break
      }
      case 'SESSION_CLOSED': {
        this.setData({ humanStatus: 'CLOSED' })
        this.pushSystemMessage('本次会话已结束。如果还需帮助，可以再次请求转人工。')
        break
      }
      case 'ERROR': {
        const msg = (b && b.message) || '服务端错误'
        wx.showToast({ title: msg, icon: 'none' })
        if (this.data.humanStatus === 'REQUESTING') {
          this.setData({ humanStatus: 'NONE' })
        }
        break
      }
      default:
        // 忽略其他类型
        break
    }
  },

  // ============== 辅助方法 ==============
  pushSystemMessage(text) {
    const id = this.data.msgCounter
    this.setData({
      msgCounter: id + 1,
      messages: [...this.data.messages, {
        id, role: 'human', isSystem: true,
        content: text,
        contentNodes: textToNodes(text),
      }],
    })
    this.scrollToBottom()
  },

  scrollToBottom() {
    setTimeout(() => {
      const lastMsg = this.data.messages[this.data.messages.length - 1]
      const target = lastMsg ? 'msg-' + lastMsg.id : 'msg-welcome'
      this.setData({ scrollToId: target })
    }, 80)
  },

  closeHumanSession() {
    if (!this.data.sessionId) return
    wx.showModal({
      title: '提示',
      content: '确定结束本次人工客服会话？',
      success: (r) => {
        if (r.confirm) {
          this.ensureWs(() => {
            this.sendWs('CLOSE', { sessionId: this.data.sessionId })
          })
        }
      },
    })
  },
})

/**
 * SSE 流式请求（wx.request 分块接收）
 *
 * 为什么不用一次性接口：模型憋完一整段要好几秒，这段时间屏幕上只有个转圈，
 * 农户会以为卡死了。流式是边生成边推，第一个字几百毫秒就到。
 *
 * 兜底逻辑（很重要）：
 *   - 基础库不支持 enableChunked 时，onChunkReceived 一次都不会触发，
 *     整段 body 会在 success 里一次性到 —— 这时退化成「一次性显示」，不会白屏；
 *   - 中途断开但已经收到内容，按正常结束处理，保留已有的半截回答；
 *   - 一个字都没收到就失败，调用方再回退到一次性接口。
 * 也就是说：流式只是「更好」，不是「唯一通路」。
 */
const { BASE_URL } = require('./request')

/**
 * 把一段字节追进累积队列，尽量多地解出字符；
 * 尾部很可能被切成半个中文（UTF-8 一个汉字 3 字节），那几个字节留到下一次再解。
 *
 * @param {number[]} acc 累积队列，原地修改
 * @param {Uint8Array} chunk 本次收到的字节
 * @returns {string} 本次解出来的文本
 */
function feedBytes(acc, chunk) {
  for (let i = 0; i < chunk.length; i++) acc.push(chunk[i])

  // 从末尾往前找首字节，判断最后一段序列收没收全
  let keep = 0
  for (let k = 1; k <= 4 && k <= acc.length; k++) {
    const b = acc[acc.length - k]
    if ((b & 0xC0) === 0x80) continue // 续字节，继续往前找
    if (b < 0x80) break // ASCII，一定是完整的
    const need = b >= 0xF0 ? 4 : (b >= 0xE0 ? 3 : 2)
    if (k < need) keep = k // 没收全，这几个字节留着
    break
  }

  const end = acc.length - keep
  const bytes = acc.slice(0, end)
  acc.splice(0, end)
  return decodeUtf8(bytes)
}

/** UTF-8 字节数组 → 字符串（小程序环境未必有 TextDecoder，自己解一遍最稳） */
function decodeUtf8(bytes) {
  let out = ''
  let i = 0
  while (i < bytes.length) {
    const b = bytes[i]
    if (b < 0x80) {
      out += String.fromCharCode(b)
      i += 1
    } else if (b >= 0xF0) {
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) |
        ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F)
      out += typeof String.fromCodePoint === 'function' ? String.fromCodePoint(cp) : '?'
      i += 4
    } else if (b >= 0xE0) {
      out += String.fromCharCode(((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F))
      i += 3
    } else if (b >= 0xC0) {
      out += String.fromCharCode(((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F))
      i += 2
    } else {
      i += 1 // 孤立续字节，丢弃
    }
  }
  return out
}

/**
 * 按行解析 SSE：只认 data: 开头的行。
 * 后端把每一小段做了 JSON 编码（文本里的换行会被 SSE 当成新行拆断），
 * 所以这里要 parse 回来；parse 不动就当纯文本用，不至于整段丢掉。
 *
 * @param {string} text 本次新增文本
 * @param {{buffer: string}} state 跨 chunk 保留的半行
 * @param {(delta: string) => void} onDelta
 */
function parseEvents(text, state, onDelta) {
  state.buffer += text
  let idx
  while ((idx = state.buffer.indexOf('\n')) >= 0) {
    const line = state.buffer.slice(0, idx).replace(/\r$/, '')
    state.buffer = state.buffer.slice(idx + 1)
    if (line.indexOf('data:') !== 0) continue

    const payload = line.slice(5).trim()
    if (!payload || payload === '[DONE]') continue

    let piece
    try {
      piece = JSON.parse(payload)
    } catch (e) {
      piece = payload
    }
    if (typeof piece === 'string' && piece) onDelta(piece)
  }
}

/**
 * 发起一次流式对话
 *
 * @param {Object} payload 与 /api/ai/chat 完全一样的请求体
 * @param {Object} handlers
 *   {Function} onDelta 每收到一小段（会调用很多次）
 *   {Function} onDone  正常结束，参数是累积的完整文本
 *   {Function} onError 失败，参数是 (err, 已收到的文本)
 * @returns {{abort: Function}} 可主动断开
 */
function streamChat(payload, handlers) {
  const onDelta = (handlers && handlers.onDelta) || function () {}
  const onDone = (handlers && handlers.onDone) || function () {}
  const onError = (handlers && handlers.onError) || function () {}

  const acc = []
  const state = { buffer: '' }
  let full = ''
  let settled = false

  const push = (delta) => {
    full += delta
    onDelta(delta)
  }

  const finish = () => {
    if (settled) return
    settled = true
    onDone(full)
  }

  const fail = (err) => {
    if (settled) return
    settled = true
    onError(err, full)
  }

  const token = wx.getStorageSync('token')
  const task = wx.request({
    url: BASE_URL + '/api/ai/chat/stream',
    method: 'POST',
    data: payload,
    header: Object.assign(
      { 'Content-Type': 'application/json' },
      token ? { Authorization: 'Bearer ' + token } : {}
    ),
    enableChunked: true,
    // 分块到达：每块可能含多行，也可能只有半行，交给 parseEvents 拼
    onChunkReceived: (res) => {
      if (!res || !res.data) return
      const text = feedBytes(acc, new Uint8Array(res.data))
      parseEvents(text, state, push)
    },
    success: (res) => {
      // 不支持分块的环境：整段 body 在这里一次性到，补解析一遍
      if (res && res.data) {
        if (typeof res.data === 'string') {
          parseEvents(res.data, state, push)
        } else if (res.data instanceof ArrayBuffer) {
          parseEvents(feedBytes(acc, new Uint8Array(res.data)), state, push)
        }
      }
      finish()
    },
    fail: (err) => {
      console.error('[AI 流式] 请求失败', err && err.errMsg)
      fail(err)
    },
  })

  return {
    abort: () => {
      settled = true
      try {
        task.abort()
      } catch (e) { /* 已经结束的 task 调 abort 会抛，忽略 */ }
    },
  }
}

module.exports = { streamChat }

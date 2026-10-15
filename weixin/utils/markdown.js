/**
 * 简易 Markdown 转 rich-text nodes 解析器
 * 支持：标题(##/###)、加粗(**)、列表(-/1.)、段落
 */

function escapeHtml(str) {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 解析行内样式：加粗 **text** */
function parseInline(text) {
  const children = []
  const boldRegex = /\*\*(.+?)\*\*/g
  let lastIndex = 0
  let match
  while ((match = boldRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      children.push({ type: 'text', text: escapeHtml(text.slice(lastIndex, match.index)) })
    }
    children.push({ name: 'span', attrs: { style: 'font-weight:bold;color:#2F6B42;' }, children: [{ type: 'text', text: escapeHtml(match[1]) }] })
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < text.length) {
    children.push({ type: 'text', text: escapeHtml(text.slice(lastIndex)) })
  }
  return children.length > 0 ? children : [{ type: 'text', text: escapeHtml(text) }]
}

// ============ 表格：降级成「键值对卡片」============
// rich-text 名义上认 table 标签，但手机上五列挤在一行既看不清也控不住边框，
// 所以这里不渲染成表格：每一行翻成一张卡片，第一列当标题，
// 其余列「字段名 + 值」纵向排开。手机上扫读比挤成一团的表强得多。

/** 占位符：保护单元格里的字面竖线（\|），拆完再换回来 */
var PIPE = '\u0000'

function splitTableRow(line) {
  var t = String(line == null ? '' : line).trim().split('\\|').join(PIPE)
  if (t.charAt(0) === '|') t = t.slice(1)
  if (t.charAt(t.length - 1) === '|') t = t.slice(0, -1)
  return t.split('|').map(function (c) { return c.split(PIPE).join('|') })
}

/** 分隔行（|---|---|）：全部单元格都是短横线才成立——这是判断表格唯一可靠的信号 */
function isDelimiterRow(line) {
  var cells = splitTableRow(line)
  if (!cells.length) return false
  return cells.every(function (c) { return /^:?-+:?$/.test(c.trim()) })
}

function tableToCards(headers, rows) {
  var cards = rows.map(function (cells) {
    var children = [{
      name: 'div',
      attrs: { style: 'margin-bottom:8rpx;font-size:28rpx;font-weight:700;color:#26332B;' },
      children: parseInline(cells[0] || ''),
    }]
    // 字段行：标签灰、值深色，用两个 span 横排（rich-text 里 flex 不保险，靠 inline 排）
    for (var k = 1; k < cells.length; k++) {
      var label = headers[k] || ''
      if (!label && !cells[k]) continue
      children.push({
        name: 'div',
        attrs: { style: 'margin:4rpx 0;font-size:26rpx;line-height:1.7;' },
        children: [
          {
            name: 'span',
            attrs: { style: 'color:#5A5F52;' },
            children: [{ type: 'text', text: label ? label + '  ' : '' }],
          },
          {
            name: 'span',
            attrs: { style: 'color:#26332B;' },
            children: parseInline(cells[k] || ''),
          },
        ],
      })
    }
    return {
      name: 'div',
      attrs: { style: 'margin:12rpx 0;padding:20rpx 24rpx;background:#FDFCF8;border:1rpx solid #E7E1D2;border-radius:16rpx;' },
      children: children,
    }
  })
  return { name: 'div', attrs: { style: 'margin:8rpx 0;' }, children: cards }
}

/** 将 Markdown 文本转换为 rich-text nodes */
function markdownToNodes(md) {
  if (!md) return []
  const lines = md.split('\n')
  const nodes = []
  const listStack = [] // 跟踪嵌套列表

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()

    // 表格：当前行含竖线、且下一行是分隔行才算开头
    const nextLine = lines[i + 1] || ''
    if (trimmed.indexOf('|') >= 0 && isDelimiterRow(nextLine)) {
      const headers = splitTableRow(trimmed).map(c => c.trim())
      const rows = []
      let j = i + 2
      while (j < lines.length) {
        const row = (lines[j] || '').trim()
        if (row === '' || row.indexOf('|') < 0) break
        rows.push(splitTableRow(row).map(c => c.trim()))
        j++
      }
      nodes.push(tableToCards(headers, rows))
      i = j - 1
      continue
    }

    // 空行：结束列表
    if (trimmed === '') {
      while (listStack.length > 0) {
        listStack.pop()
      }
      continue
    }

    // 标题
    const h2Match = trimmed.match(/^## (.+)/)
    if (h2Match) {
      while (listStack.length > 0) listStack.pop()
      nodes.push({
        name: 'h2',
        attrs: { style: 'font-size:34rpx;font-weight:700;color:#26332B;margin:20rpx 0 12rpx;padding-bottom:8rpx;border-bottom:2rpx solid #2F6B42;' },
        children: [{ type: 'text', text: escapeHtml(h2Match[1]) }]
      })
      continue
    }

    const h3Match = trimmed.match(/^### (.+)/)
    if (h3Match) {
      while (listStack.length > 0) listStack.pop()
      nodes.push({
        name: 'h3',
        attrs: { style: 'font-size:30rpx;font-weight:600;color:#2F6B42;margin:16rpx 0 8rpx;' },
        children: [{ type: 'text', text: escapeHtml(h3Match[1]) }]
      })
      continue
    }

    const h4Match = trimmed.match(/^#### (.+)/)
    if (h4Match) {
      while (listStack.length > 0) listStack.pop()
      nodes.push({
        name: 'h4',
        attrs: { style: 'font-size:28rpx;font-weight:600;color:#6B6F63;margin:12rpx 0 6rpx;' },
        children: [{ type: 'text', text: escapeHtml(h4Match[1]) }]
      })
      continue
    }

    // 无序列表
    const ulMatch = trimmed.match(/^- (.+)/)
    if (ulMatch) {
      if (listStack.length === 0 || listStack[listStack.length - 1].type !== 'ul') {
        const ul = { name: 'ul', attrs: { style: 'margin:8rpx 0;padding-left:24rpx;' }, children: [] }
        nodes.push(ul)
        listStack.push({ type: 'ul', node: ul })
      }
      const ul = listStack[listStack.length - 1].node
      ul.children.push({
        name: 'li',
        attrs: { style: 'margin:6rpx 0;line-height:1.6;font-size:28rpx;color:#6B6F63;' },
        children: parseInline(ulMatch[1])
      })
      continue
    }

    // 有序列表
    const olMatch = trimmed.match(/^\d+\. (.+)/)
    if (olMatch) {
      if (listStack.length === 0 || listStack[listStack.length - 1].type !== 'ol') {
        const ol = { name: 'ol', attrs: { style: 'margin:8rpx 0;padding-left:24rpx;' }, children: [] }
        nodes.push(ol)
        listStack.push({ type: 'ol', node: ol })
      }
      const ol = listStack[listStack.length - 1].node
      ol.children.push({
        name: 'li',
        attrs: { style: 'margin:6rpx 0;line-height:1.6;font-size:28rpx;color:#6B6F63;' },
        children: parseInline(olMatch[1])
      })
      continue
    }

    // 分割线
    if (trimmed.match(/^[-*_]{3,}$/)) {
      while (listStack.length > 0) listStack.pop()
      nodes.push({
        name: 'hr',
        attrs: { style: 'border:none;border-top:1rpx solid #EFEADA;margin:16rpx 0;' }
      })
      continue
    }

    // 引用
    const bqMatch = trimmed.match(/^> (.+)/)
    if (bqMatch) {
      while (listStack.length > 0) listStack.pop()
      nodes.push({
        name: 'blockquote',
        attrs: { style: 'border-left:6rpx solid #2F6B42;padding:8rpx 16rpx;margin:12rpx 0;background:rgba(47,107,66,0.08);' },
        children: [{
          name: 'p',
          attrs: { style: 'font-size:26rpx;color:#555;line-height:1.6;' },
          children: parseInline(bqMatch[1])
        }]
      })
      continue
    }

    // 普通段落
    while (listStack.length > 0) listStack.pop()
    nodes.push({
      name: 'p',
      attrs: { style: 'margin:8rpx 0;line-height:1.7;font-size:28rpx;color:#6B6F63;' },
      children: parseInline(trimmed)
    })
  }

  return nodes
}

module.exports = { markdownToNodes }
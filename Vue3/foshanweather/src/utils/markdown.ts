function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function parseInline(text: string): string {
  let result = escapeHtml(text)
  result = result.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  result = result.replace(/`([^`]+)`/g, '<code>$1</code>')
  return result
}

/**
 * 取正则捕获组。所有这里的正则都要求 `(.+)` 至少一个字符，
 * 匹配成功时组 1 必然存在，`?? ''` 只是为了满足 noUncheckedIndexedAccess。
 */
function group(match: RegExpMatchArray | null): string {
  return match?.[1] ?? ''
}

/**
 * 单元格内的字面竖线（`\|`）先用占位符盖住，拆完再换回来，
 * 否则 `| 用 A \| B 表示 |` 会被拆成三格。
 */
const PIPE = '\u0000'

/** 拆一行表格：首尾管道符可省略 */
function splitTableRow(line: string): string[] {
  let t = line.trim().split('\\|').join(PIPE)
  if (t.startsWith('|')) t = t.slice(1)
  if (t.endsWith('|')) t = t.slice(0, -1)
  return t.split('|').map((c) => c.split(PIPE).join('|'))
}

/** 分隔行（|---|---|、|:--:|、|---:|）：全部单元格都是短横线才成立 */
function isDelimiterRow(line: string): boolean {
  const cells = splitTableRow(line)
  return cells.length > 0 && cells.every((c) => /^:?-+:?$/.test(c.trim()))
}

/** 从分隔行读对齐方式：:-- 左 / --: 右 / :-: 居中 */
function alignOf(delim: string): string {
  const d = delim.trim()
  const left = d.startsWith(':')
  const right = d.endsWith(':')
  if (left && right) return 'center'
  if (right) return 'right'
  return 'left'
}

export function markdownToHtml(md: string): string {
  if (!md) return ''
  const lines = md.split('\n')
  const html: string[] = []
  let inList: 'ul' | 'ol' | null = null

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] ?? ''
    const trimmed = line.trim()

    if (trimmed === '') {
      if (inList) {
        html.push(`</${inList}>`)
        inList = null
      }
      continue
    }

    // 表格：当前行含竖线、且下一行是分隔行才成立。
    // 只靠「含 | 」判断会把正文里出现的竖线误当表格，分隔行是唯一可靠的信号。
    const nextLine = lines[i + 1] ?? ''
    if (trimmed.includes('|') && isDelimiterRow(nextLine)) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      const headers = splitTableRow(trimmed)
      const aligns = splitTableRow(nextLine).map(alignOf)
      const rows: string[][] = []
      let j = i + 2
      while (j < lines.length) {
        const row = (lines[j] ?? '').trim()
        if (row === '' || !row.includes('|')) break
        rows.push(splitTableRow(row))
        j++
      }
      const cell = (tag: 'th' | 'td', text: string, col: number) =>
        `<${tag} style="text-align:${aligns[col] ?? 'left'}">${parseInline(text.trim())}</${tag}>`
      const head = headers.map((h, k) => cell('th', h, k)).join('')
      const body = rows
        .map((cells) => `<tr>${headers.map((_, k) => cell('td', cells[k] ?? '', k)).join('')}</tr>`)
        .join('')
      html.push(`<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>`)
      i = j - 1
      continue
    }

    const h1Match = trimmed.match(/^# (.+)/)
    if (h1Match) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push(`<h1>${parseInline(group(h1Match))}</h1>`)
      continue
    }

    const h2Match = trimmed.match(/^## (.+)/)
    if (h2Match) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push(`<h2>${parseInline(group(h2Match))}</h2>`)
      continue
    }

    const h3Match = trimmed.match(/^### (.+)/)
    if (h3Match) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push(`<h3>${parseInline(group(h3Match))}</h3>`)
      continue
    }

    const h4Match = trimmed.match(/^#### (.+)/)
    if (h4Match) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push(`<h4>${parseInline(group(h4Match))}</h4>`)
      continue
    }

    const ulMatch = trimmed.match(/^[-*] (.+)/)
    if (ulMatch) {
      if (inList !== 'ul') {
        if (inList) html.push(`</${inList}>`)
        html.push('<ul>')
        inList = 'ul'
      }
      html.push(`<li>${parseInline(group(ulMatch))}</li>`)
      continue
    }

    const olMatch = trimmed.match(/^\d+\. (.+)/)
    if (olMatch) {
      if (inList !== 'ol') {
        if (inList) html.push(`</${inList}>`)
        html.push('<ol>')
        inList = 'ol'
      }
      html.push(`<li>${parseInline(group(olMatch))}</li>`)
      continue
    }

    if (trimmed.match(/^[-*_]{3,}$/)) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push('<hr>')
      continue
    }

    const bqMatch = trimmed.match(/^> (.+)/)
    if (bqMatch) {
      if (inList) { html.push(`</${inList}>`); inList = null }
      html.push(`<blockquote><p>${parseInline(group(bqMatch))}</p></blockquote>`)
      continue
    }

    if (inList) {
      html.push(`</${inList}>`)
      inList = null
    }
    html.push(`<p>${parseInline(trimmed)}</p>`)
  }

  if (inList) {
    html.push(`</${inList}>`)
  }

  return html.join('\n')
}

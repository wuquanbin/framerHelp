/**
 * 帮扶台账的公共定义
 * <p>
 * 以前「农情上报页登记台账」和「台账页手工补录」各写了一份：类型数组两份、
 * 上报类型到帮扶类型的映射两份（台账页那份还漏了 other）、文案拼接逻辑也各写一遍。
 * 结果就是同一条上报在两个入口预填出来的台账类型可能不一样。这里统一收口。
 * </p>
 */

/** 帮扶类型（顺序即下拉框顺序） */
export const ASSISTANCE_TYPES = [
  '病虫害防治',
  '灾损救助',
  '长势指导',
  '诉求帮扶',
  '物资帮扶',
  '综合帮扶',
] as const

/** 台账状态 */
export const ASSISTANCE_STATUS = ['已完成', '进行中', '待跟进'] as const

/** 映射不上时的兜底类型 */
export const DEFAULT_ASSISTANCE_TYPE = '综合帮扶'

/** 帮扶内容长度上限，与输入框 maxlength 保持一致 */
export const MAX_DESCRIPTION = 250

/** 农情上报类型 -> 帮扶类型（必须覆盖 FarmReport 的全部 reportType，含 other） */
const REPORT_TYPE_TO_ASSISTANCE: Record<string, string> = {
  pest: '病虫害防治',
  disaster: '灾损救助',
  growth: '长势指导',
  help: '诉求帮扶',
  other: '综合帮扶',
}

/**
 * 由上报类型推帮扶类型
 *
 * @param reportType 上报类型（pest/disaster/growth/help/other）
 */
export function assistanceTypeOf(reportType?: string | null): string {
  if (!reportType) return DEFAULT_ASSISTANCE_TYPE
  return REPORT_TYPE_TO_ASSISTANCE[reportType] || DEFAULT_ASSISTANCE_TYPE
}

/**
 * 拼台账描述：标题与详情合并，详情里已包含标题时不重复拼接，并限长。
 */
export function buildLedgerText(title?: string | null, detail?: string | null): string {
  const t = (title || '').trim()
  const d = (detail || '').trim()
  let text: string
  if (!d) text = t
  else if (!t || d.includes(t)) text = d
  else text = `${t}：${d}`
  return text.length > MAX_DESCRIPTION ? text.slice(0, MAX_DESCRIPTION) : text
}

/** 台账表单的预填草稿 */
export interface LedgerDraft {
  assistanceType: string
  description: string
  status: string
}

/**
 * 按一条农情上报生成台账预填草稿。
 * 台账是结案记录，所以默认状态给「已完成」。
 */
export function buildLedgerDraft(report: {
  reportType?: string | null
  title?: string | null
  description?: string | null
}): LedgerDraft {
  return {
    assistanceType: assistanceTypeOf(report.reportType),
    description: buildLedgerText(report.title, report.description),
    status: '已完成',
  }
}

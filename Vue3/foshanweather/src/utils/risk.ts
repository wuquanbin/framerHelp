/**
 * 作物风险等级的展示口径。
 *
 * 后端 `CropProfile.riskLevel` 是整数 1–5。所有页面的风险等级显示、
 * 标签配色与编辑提交统一走这里，保证口径一致、提交值与 Integer 字段匹配。
 */

/** 下标即等级值，0 位留空以便直接用 level 取值 */
export const RISK_LABELS = ['', '低风险', '较低风险', '中等风险', '较高风险', '高风险']

/** 把任意来源的等级值收敛到 1–5；无法识别时返回 0 */
export function normalizeRisk(level: unknown): number {
  const n = Number(level)
  if (!n || Number.isNaN(n)) return 0
  return Math.min(5, Math.max(1, Math.round(n)))
}

/** 等级 → 中文标签 */
export function riskLabel(level: unknown): string {
  const n = normalizeRisk(level)
  // normalizeRisk 已把值收敛到 0 或 1–5；0 位留空，取到空串时同样回退到「暂无评估」
  return n ? RISK_LABELS[n] ?? '暂无评估' : '暂无评估'
}

/** 等级 → el-tag 的 type */
export function riskTagType(level: unknown): 'success' | 'warning' | 'danger' | 'info' {
  const n = normalizeRisk(level)
  if (!n) return 'info'
  if (n >= 4) return 'danger'
  if (n === 3) return 'warning'
  return 'success'
}

/** 等级 → 数据概览玻璃卡用的 class */
export function riskClass(level: unknown): string {
  const n = normalizeRisk(level)
  if (n >= 4) return 'db-lv-high'
  if (n === 3) return 'db-lv-mid'
  return 'db-lv-low'
}

/** 表单下拉选项 */
export const RISK_OPTIONS = [1, 2, 3, 4, 5].map((v) => ({ value: v, label: RISK_LABELS[v] }))

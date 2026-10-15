/**
 * 后台三级角色权限模型（前端侧）。
 *
 * 后端是唯一的权限判定者（SecurityConfig 按 ROLE_* 拦截接口），
 * 前端这一层只做「菜单/按钮可见性」—— 目的是不让用户看到点了会 403 的入口，
 * 而不是替代后端鉴权。任何写操作接口都必须由后端独立校验。
 */

export type AdminRole = 'SUPER_ADMIN' | 'EXPERT' | 'VILLAGE_ADMIN'

/** 角色中文名（后端 roleLabel 缺失时兜底） */
export const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  EXPERT: '专家',
  VILLAGE_ADMIN: '村镇管理员',
  // ADMIN：库中超级管理员账号使用的历史值，按超级管理员处理
  ADMIN: '超级管理员',
  // 已下线的细分角色，统一按专家处理
  DATA_ADMIN: '专家',
  CONTENT_ADMIN: '专家',
}

/**
 * 归一化角色：无法识别的角色一律按 EXPERT 处理，与后端 AdminRole.normalize 的降级规则一致。
 */
export function normalizeRole(role: string | undefined | null): AdminRole {
  const r = (role || '').trim().toUpperCase()
  if (r === 'SUPER_ADMIN') return 'SUPER_ADMIN'
  if (r === 'VILLAGE_ADMIN') return 'VILLAGE_ADMIN'
  // ADMIN 必须按超管处理：降级会锁死唯一能管账号的人
  if (r === 'ADMIN') return 'SUPER_ADMIN'
  return 'EXPERT'
}

export function roleLabel(role: string | undefined | null): string {
  return ROLE_LABELS[(role || '').trim().toUpperCase()] || '专家'
}

/** Element Plus 的 el-tag type：超级管理员=危险红、村镇=成功绿、专家=警告金 */
export function roleTagType(role: string | undefined | null): 'danger' | 'success' | 'warning' {
  const r = normalizeRole(role)
  if (r === 'SUPER_ADMIN') return 'danger'
  if (r === 'VILLAGE_ADMIN') return 'success'
  return 'warning'
}

export function isSuperAdmin(role: string | undefined | null): boolean {
  return normalizeRole(role) === 'SUPER_ADMIN'
}

/** 能不能进「人员管理 / 系统设置 / 操作日志」 */
export function canManageSystem(role: string | undefined | null): boolean {
  return normalizeRole(role) === 'SUPER_ADMIN'
}

/** 能不能删除业务数据（村镇管理员不可删） */
export function canDelete(role: string | undefined | null): boolean {
  const r = normalizeRole(role)
  return r === 'SUPER_ADMIN' || r === 'EXPERT'
}

/**
 * 能不能维护村镇基础数据（新增/编辑）：超管 + 村镇管理员。
 * 村镇管理员能改哪几个村由后端 `assertVillageInScope` 判定，不看前端传什么。
 */
export function canEditVillage(role: string | undefined | null): boolean {
  const r = normalizeRole(role)
  return r === 'SUPER_ADMIN' || r === 'VILLAGE_ADMIN'
}

/** 删村镇日常台账（作物档案 / 灾害记录）：三类角色都可，越权由后端 scope 校验拦住，所以恒 true。 */
export function canDeleteVillageData(): boolean {
  return true
}

/** 编辑提示词模板（新增/修改/新建版本）：超管 + 专家；启用与删除走 isSuperAdmin。 */
export function canEditPrompt(role: string | undefined | null): boolean {
  const r = normalizeRole(role)
  return r === 'SUPER_ADMIN' || r === 'EXPERT'
}

/**
 * 判断某菜单/路由是否对当前角色可见。
 * 未声明 roles 的条目表示所有已登录角色都可见。
 */
export function hasMenuAccess(
  role: string | undefined | null,
  roles?: AdminRole[],
): boolean {
  if (!roles || roles.length === 0) return true
  const current = normalizeRole(role)
  return roles.includes(current)
}

import axios, { type AxiosResponse } from 'axios'
import { API_BASE } from '@/config/api'
import type {
  ApiResponse,
  DashboardOverview,
  CurrentWeather,
  VillageOption,
  AdminUser,
  User,
  Feedback,
  FarmReport,
  FarmResponse,
  OperationLog,
  AppConfig,
  HumanChatSession,
  HumanChatMessage,
} from '@/types/weather'

// 导出 BASE_URL 供 WebSocket 等非 axios 场景复用（地址集中在 src/config/api.ts）
export const BASE_URL = API_BASE

const http = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// axios 官方类型要求 fulfilled 的产出与入参同型（V | Promise<V>），
// 但本项目在拦截器里剥掉了 ApiResponse 外壳、只往外吐 data，属于「换型」拦截器，
// 官方类型表达不了。这里断言一次，好过在每个 api 函数里各写一次 as。
type ResponseFulfilled = Parameters<typeof http.interceptors.response.use>[0]
http.interceptors.response.use(
  ((response: AxiosResponse<ApiResponse<unknown>>) => {
    const payload = response.data
    if (!payload.success) {
      return Promise.reject(new Error(payload.message || '请求失败'))
    }
    return payload.data
  }) as ResponseFulfilled,
  (error) => {
    const status = error.response?.status
    const isLoginRequest = /\/admin\/login\b/.test(error.config?.url ?? '')
    // 登录页自身的 401 表示“用户名或密码错”，是正常业务反馈：
    // 不清理会话、不整页 reload，交给登录页就地展示人话错误
    const onLoginPage = window.location.pathname.startsWith('/login')
    if ((status === 401 || status === 403) && !isLoginRequest && !onLoginPage) {
      localStorage.removeItem('admin_token')
      localStorage.removeItem('admin_user')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  },
)

// ========== 列表响应归一化 ==========
// 后端列表接口可能返回裸数组，也可能返回分页结构 { items, total, page, size }：
// 只需要行数据用 asRows()，需要总数/页码用 asPage()。

/** 后端分页响应结构（与 Java 端 PageResult 对应） */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 从数组或分页结构中取出行数据 */
export function asRows<T>(data: unknown): T[] {
  if (Array.isArray(data)) return data as T[]
  const d = data as PageResult<T> | null | undefined
  if (d && Array.isArray(d.items)) return d.items
  return []
}

/** 归一化为完整分页对象（total 缺失时退化为 items 长度） */
export function asPage<T>(data: unknown): PageResult<T> {
  if (Array.isArray(data)) {
    return { items: data as T[], total: data.length, page: 0, size: data.length || 20 }
  }
  const d = data as PageResult<T> | null | undefined
  if (d && Array.isArray(d.items)) {
    return { items: d.items, total: d.total ?? d.items.length, page: d.page ?? 0, size: d.size ?? 20 }
  }
  return { items: [], total: 0, page: 0, size: 20 }
}

// ========== 公开 API ==========

export function getVillages(): Promise<VillageOption[]> {
  return http.get('/meta/villages')
}

export function getDashboardOverview(villageCode: string): Promise<DashboardOverview> {
  const query = villageCode ? `?villageCode=${encodeURIComponent(villageCode)}` : ''
  return http.get(`/dashboard/overview${query}`)
}

/** 单个村镇的实况天气（村镇温度对比用） */
export function getCurrentWeather(villageCode: string): Promise<CurrentWeather> {
  return http.get('/weather/current', { params: { villageCode } })
}

// ========== 管理员认证 ==========

export function adminLogin(
  username: string,
  password: string,
): Promise<{
  token: string
  username: string
  realName: string
  role: string
  roleLabel: string
  villageCode: string | null
}> {
  return http.post('/admin/login', { username, password })
}

export function getAdminInfo(): Promise<{
  username: string
  realName: string
  role: string
  roleLabel: string
  villageCode: string | null
  phone: string
  email: string
}> {
  return http.get('/admin/info')
}

// ========== 管理后台统计 ==========

export function getAdminStats(): Promise<Record<string, number>> {
  return http.get('/admin/dashboard/stats')
}

export function getCropDistribution(): Promise<Array<{ name: string; value: number }>> {
  return http.get('/admin/dashboard/crop-distribution')
}

// ========== 村镇管理 CRUD ==========

export function getAdminVillages(): Promise<VillageOption[]> {
  return http.get('/admin/villages')
}
export function createVillage(data: unknown) {
  return http.post('/admin/villages', data)
}
export function updateVillage(id: number, data: unknown) {
  return http.put(`/admin/villages/${id}`, data)
}
export function deleteVillage(id: number, force = false) {
  return http.delete(`/admin/villages/${id}`, { params: force ? { force: true } : undefined })
}

// ========== 高德地图地点搜索（地图选址） ==========

export interface AmapPlace {
  name: string
  address: string
  location: string
  adcode: string
  province: string
  city: string
  district: string
}

export function searchAmapPlace(keywords: string, city?: string) {
  return http.get<AmapPlace[]>('/admin/amap/place-search', {
    params: { keywords, city },
  })
}

/** 静态地图图片（高德 staticmap 代理）— 返回 ObjectURL 供 <img> 显示 */
export async function getAmapStaticMap(location: string): Promise<string> {
  const res = await axios.get(`${BASE_URL}/admin/amap/static-map`, {
    params: { location, zoom: 15 },
    responseType: 'blob',
    headers: { Authorization: `Bearer ${localStorage.getItem('admin_token')}` },
    timeout: 15000,
  })
  return URL.createObjectURL(res.data)
}

// ========== 作物档案 CRUD ==========

export function getAdminCrops() {
  return http.get('/admin/crops')
}
/**
 * 后台列表走服务端分页（避免首屏读整表）
 * <p>
 * 筛选条件一并下推给后端：村镇管理员的 scope 在后端会覆盖 villageCode，
 * 所以前端传什么都翻不出自己的数据范围。
 * </p>
 */
export async function getAdminCropsPaged(params: {
  page: number
  size: number
  villageCode?: string
  cropType?: string
}) {
  return asPage<unknown>(await http.get('/admin/crops', { params }))
}
export function createCrop(data: unknown) {
  return http.post('/admin/crops', data)
}
export function updateCrop(id: number, data: unknown) {
  return http.put(`/admin/crops/${id}`, data)
}
export function deleteCrop(id: number) {
  return http.delete(`/admin/crops/${id}`)
}

// ========== 农事建议 CRUD ==========

export function getAdminFarmAdvices() {
  return http.get('/admin/farm-advices')
}
/** 后台列表走服务端分页（避免首屏读整表） */
export async function getAdminFarmAdvicesPaged(params: { page: number; size: number }) {
  return asPage<unknown>(await http.get('/admin/farm-advices', { params }))
}
export function createFarmAdvice(data: unknown) {
  return http.post('/admin/farm-advices', data)
}
export function updateFarmAdvice(id: number, data: unknown) {
  return http.put(`/admin/farm-advices/${id}`, data)
}
export function deleteFarmAdvice(id: number) {
  return http.delete(`/admin/farm-advices/${id}`)
}

// ========== 灾害记录 CRUD ==========

export async function getAdminDisasters() {
  return asRows<unknown>(await http.get('/admin/disasters'))
}
/** 分页查询，返回完整分页对象（含 total） */
export async function getAdminDisastersPaged(params: { page: number; size: number }) {
  return asPage<unknown>(await http.get('/admin/disasters', { params }))
}
export function createDisaster(data: unknown) {
  return http.post('/admin/disasters', data)
}
export function updateDisaster(id: number, data: unknown) {
  return http.put(`/admin/disasters/${id}`, data)
}
export function deleteDisaster(id: number) {
  return http.delete(`/admin/disasters/${id}`)
}

// ========== 帮扶台账 CRUD ==========

export async function getAdminAssistanceRecords() {
  return asRows<unknown>(await http.get('/admin/assistance-records'))
}
/** 分页查询 */
export async function getAdminAssistanceRecordsPaged(params: { page: number; size: number }) {
  return asPage<unknown>(await http.get('/admin/assistance-records', { params }))
}
export function createAssistanceRecord(data: unknown) {
  return http.post('/admin/assistance-records', data)
}
export function updateAssistanceRecord(id: number, data: unknown) {
  return http.put(`/admin/assistance-records/${id}`, data)
}
export function deleteAssistanceRecord(id: number) {
  return http.delete(`/admin/assistance-records/${id}`)
}

// ========== 帮扶成效看板 ==========

/** 看板总览指标（比率无数据时为 null，区分「没数据」和「0%」） */
export interface AssistanceOverview {
  totalRecords: number
  helpedFarmers: number
  monthNew: number
  warningTotal: number
  reportTotal: number
  reportResolved: number
  reportResolveRate: number | null
  feedbackTotal: number
  feedbackHandled: number
  feedbackHandleRate: number | null
  taskTodayTotal: number
  taskTodayDone: number
  taskTodayRate: number | null
}

/** 响应时效（上报 → 首次帮扶响应）。无数据时 avgHours / 各比率均为 null */
export interface AssistanceResponse {
  reportTotal: number
  respondedReports: number
  coverageRate: number | null
  avgHours: number | null
  within24hCount: number
  within24hRate: number | null
}

export interface AssistanceRecentItem {
  id: number
  type: string
  description: string | null
  status: string | null
  /** 后端格式化好的 yyyy-MM-dd HH:mm */
  createdAt: string | null
  farmerName: string
  villageName: string
  reportId: number | null
  /** 首次响应耗时（小时，1 位小数）；手工建档或未响应时为 null */
  responseHours: number | null
}

export interface AssistanceDashboard {
  overview: AssistanceOverview
  byType: { type: string; count: number }[]
  byVillage: { villageCode: string | null; villageName: string; count: number }[]
  byCrop: { cropName: string; count: number }[]
  response: AssistanceResponse
  trend: { date: string; count: number }[]
  recent: AssistanceRecentItem[]
}

/** 帮扶成效看板聚合数据（单接口返回） */
export function getAssistanceDashboard() {
  return http.get('/admin/assistance-dashboard') as Promise<AssistanceDashboard>
}

/**
 * 导出看板为带排版的 Excel（.xlsx）。
 * 后端直接返回字节流（无 ApiResponse 包装），需绕开响应拦截器，
 * 用裸 axios 拿 blob 再触发浏览器下载。
 */
export async function exportAssistanceExcel(): Promise<void> {
  const res = await axios.get(`${BASE_URL}/admin/assistance-dashboard/export`, {
    responseType: 'blob',
    headers: { Authorization: `Bearer ${localStorage.getItem('admin_token')}` },
    timeout: 30000,
  })
  // 后端把文件名放在 Content-Disposition，前端优先取它，取不到再按日期兜底
  const disposition: string = res.headers?.['content-disposition'] || ''
  const matched = /filename="?([^";]+)"?/.exec(disposition)
  const fallback = `assistance-report-${new Date().toISOString().slice(0, 10)}.xlsx`
  const filename = matched?.[1] || fallback

  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// ========== 农户管理 ==========

export async function getAdminUsers(): Promise<User[]> {
  return asRows<User>(await http.get('/admin/users'))
}
/** 分页查询：关键词/状态/村镇筛选由后端处理，保证翻页时筛选仍然生效 */
export async function getAdminUsersPaged(params: {
  page: number
  size: number
  keyword?: string
  status?: number
  villageCode?: string
}): Promise<PageResult<User>> {
  return asPage<User>(await http.get('/admin/users', { params }))
}
export function getAdminUser(id: number): Promise<User> {
  return http.get(`/admin/users/${id}`)
}
export function updateUserStatus(id: number, status: number) {
  return http.put(`/admin/users/${id}/status`, { status })
}

/**
 * 删除农户账号。
 * 仅超级管理员可调 —— 后端对 DELETE /admin/users/** 也只放行 SUPER_ADMIN，
 * 会连带清掉该农户的帮扶台账、反馈、我的作物与农事记录（农情上报保留）。
 */
export function deleteUser(id: number) {
  return http.delete(`/admin/users/${id}`)
}

// ========== 反馈管理 ==========

export async function getAdminFeedbacks(): Promise<Feedback[]> {
  return asRows<Feedback>(await http.get('/admin/feedbacks'))
}
/** 分页查询（可按状态过滤） */
export async function getAdminFeedbacksPaged(params: {
  page: number
  size: number
  status?: string
}): Promise<PageResult<Feedback>> {
  return asPage<Feedback>(await http.get('/admin/feedbacks', { params }))
}
export function updateFeedback(id: number, data: unknown) {
  return http.put(`/admin/feedbacks/${id}`, data)
}

// ========== 管理员管理 ==========

export function getAdminAdmins(): Promise<AdminUser[]> {
  return http.get('/admin/admins')
}
export function createAdmin(data: unknown) {
  return http.post('/admin/admins', data)
}
export function updateAdmin(id: number, data: unknown) {
  return http.put(`/admin/admins/${id}`, data)
}
export function deleteAdmin(id: number) {
  return http.delete(`/admin/admins/${id}`)
}
export function updateAdminStatus(id: number, status: number) {
  return http.put(`/admin/admins/${id}/status`, { status })
}

// ========== 操作日志 ==========

export function getOperationLogsPaged(params: {
  page: number
  size: number
  operator?: string
  module?: string
  type?: string
  startDate?: string
  endDate?: string
}): Promise<{ content: OperationLog[]; totalElements: number }> {
  return http.get('/admin/operation-logs', { params })
}

// ========== AI 对话 ==========

export function adminSendAiChat(data: { message: string; villageCode: string; history?: Array<{ role: string; content: string }> }): Promise<{ reply: string; villageCode: string; villageName: string }> {
  return http.post('/ai/chat', data, { timeout: 120000 })
}

// ========== AI 生成记录（落库，替代原来的浏览器本地存储） ==========

export interface AiGenerationRecord {
  id: number
  operatorName: string
  toolTitle: string
  villageCode: string | null
  prompt: string
  content: string
  published: boolean
  publishedAdviceId: number | null
  createdAt: string
}

export function listAiGenerations(): Promise<AiGenerationRecord[]> {
  return http.get('/admin/ai-generations')
}
export function saveAiGeneration(data: {
  toolTitle: string
  villageCode?: string
  prompt?: string
  content: string
}): Promise<AiGenerationRecord> {
  return http.post('/admin/ai-generations', data)
}
export function deleteAiGeneration(id: number) {
  return http.delete(`/admin/ai-generations/${id}`)
}
export function markAiGenerationPublished(id: number, adviceId?: number): Promise<AiGenerationRecord> {
  return http.put(`/admin/ai-generations/${id}/publish`, null, adviceId ? { params: { adviceId } } : {})
}

// ========== 农情管理 ==========

export async function getAdminFarmReports(): Promise<FarmReport[]> {
  return asRows<FarmReport>(await http.get('/admin/farm-reports'))
}
/** 分页查询（可按状态/类型过滤） */
export async function getAdminFarmReportsPaged(params: {
  page: number
  size: number
  status?: string
  type?: string
}): Promise<PageResult<FarmReport>> {
  return asPage<FarmReport>(await http.get('/admin/farm-reports', { params }))
}
export function updateFarmReportStatus(id: number, status: string) {
  // body 不能传 null/undefined：axios 对空 body 的 PUT 会默认带 application/x-www-form-urlencoded 头，
  // 后端会报 415；状态仍走 params(query)，body 传 {} 仅为让 Content-Type 保持 application/json
  return http.put(`/admin/farm-reports/${id}/status`, {}, { params: { status } })
}
export function deleteFarmReport(id: number) {
  return http.delete(`/admin/farm-reports/${id}`)
}
/** 单条农情上报详情（台账跳转定位等不在当前分页内的场景用） */
export function getAdminFarmReportDetail(id: number): Promise<FarmReport> {
  return http.get(`/admin/farm-reports/${id}`)
}
export function getAdminFarmResponses(reportId: number): Promise<FarmResponse[]> {
  return http.get('/admin/farm-responses', { params: { reportId } })
}
export function deleteFarmResponse(id: number) {
  return http.delete(`/admin/farm-responses/${id}`)
}
export function addFarmResponse(reportId: number, data: { responderOpenid: string; responderName: string; responseType: string; content: string }): Promise<FarmResponse> {
  return http.post(`/admin/farm-reports/${reportId}/responses`, data)
}

// ========== 系统配置 ==========

export function getAppConfigs(): Promise<AppConfig[]> {
  return http.get('/admin/config')
}
export function updateAppConfig(id: number, data: { configValue: string; description: string }): Promise<AppConfig> {
  return http.put(`/admin/config/${id}`, data)
}
export function createAppConfig(data: { configKey: string; configValue: string; description: string; category: string }): Promise<AppConfig> {
  return http.post('/admin/config', data)
}
export function deleteAppConfig(id: number) {
  return http.delete(`/admin/config/${id}`)
}

/** 系统运行状况：服务/数据库/运行时长/JVM/天气最后同步/版本，全部由后端真查一次 */
export interface SystemHealth {
  service: string
  database: string
  uptimeSeconds: number
  jvmUsedMb: number
  jvmMaxMb: number
  weatherLastSyncAt: string | null
  version: string
}
export function getSystemHealth(): Promise<SystemHealth> {
  return http.get('/admin/system/health')
}

// ========== 操作日志管理 ==========

export function clearOperationLogs() {
  return http.delete('/admin/operation-logs')
}

// ========== 人工客服实时聊天 ==========

export function getOnlineExperts(): Promise<{ onlineCount: number; experts: Record<string, string> }> {
  return http.get('/human-chat/online-experts')
}

export function listHumanChatSessions(status?: string): Promise<{ list: HumanChatSession[]; onlineAdmins: Record<string, string>; totalUnread: number }> {
  return http.get('/admin/human-chat/sessions', status ? { params: { status } } : {})
}

export function listHumanChatMessages(sessionId: number): Promise<HumanChatMessage[]> {
  return http.get(`/admin/human-chat/sessions/${sessionId}/messages`)
}

export function closeHumanChatSession(sessionId: number): Promise<HumanChatSession> {
  return http.put(`/admin/human-chat/sessions/${sessionId}/close`)
}

export function markHumanChatRead(sessionId: number): Promise<HumanChatSession> {
  return http.put(`/admin/human-chat/sessions/${sessionId}/read`)
}

// ========== 提示词模板 ==========
// 提示词原先硬编码在后端 AiChatServiceImpl 里，改一个字都要重新编译。
// 搬到这张表之后，后台改完立即生效（缓存 2 分钟，写操作会主动清），
// 做提示词对照实验时也能说清「哪组数据用的是哪一版」。

export async function getPromptTemplates() {
  return asRows<unknown>(await http.get('/admin/prompt-templates'))
}

/** 新建一个版本（版本号自动 +1 并启用，同编码旧版本自动停用） */
export function createPromptTemplate(data: unknown) {
  return http.post('/admin/prompt-templates', data)
}

export function updatePromptTemplate(id: number, data: unknown) {
  return http.put(`/admin/prompt-templates/${id}`, data)
}

/** 启用指定版本 —— 回滚到旧版本就调它 */
export function activatePromptTemplate(id: number) {
  return http.post(`/admin/prompt-templates/${id}/activate`)
}

export function deletePromptTemplate(id: number) {
  return http.delete(`/admin/prompt-templates/${id}`)
}

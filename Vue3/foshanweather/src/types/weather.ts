export interface ApiResponse<T> {
  success: boolean
  message: string
  data: T
  timestamp: string
}

// ========== 核心业务类型 ==========

export interface VillageOption {
  id: number
  code: string
  name: string
  mainCrops: string
  terrain: string
  assistanceLevel: string
}

export interface CurrentWeather {
  villageCode: string
  villageName: string
  weatherType: string
  temperature: number
  apparentTemperature: number
  humidity: number
  windDirection: string
  windScale: string
  airQuality: string
  precipitationProbability: number
  comfortLevel: string
  uvLevel: string
  farmIndex: string
  pressure: number | null
  visibility: number | null
  observationTime: string
}

export interface ForecastDay {
  forecastDate: string
  weekLabel: string
  weatherType: string
  lowTemperature: number
  highTemperature: number
  precipitationProbability: number
  windDirection: string
  windScale: string
  farmingAdvice: string
}

export interface FarmAdviceItem {
  title: string
  summary: string
  recommendation: string
  priorityTag: string
  iconKey: string
  priority: number
}

export interface CropProfileItem {
  id: number
  villageCode: string
  villageName: string
  cropName: string
  cropType: string
  plantingArea: string
  growthStage: string
  isMainCrop: boolean
  suitableWeather: string
  riskWeather: string
  riskLevel: number
  farmingNotes: string
  matchReason: string
}

export interface DisasterRecordItem {
  id: number
  villageCode: string
  villageName: string
  disasterName: string
  affectedArea: string
  disasterType: string
  triggerWeather: string
  description: string
  remedy: string
  severity: string
}

export interface DashboardOverview {
  village: VillageOption
  currentWeather: CurrentWeather
  forecast: ForecastDay[]
  farmAdvices: FarmAdviceItem[]
  cropProfiles: CropProfileItem[]
  disasterRecords: DisasterRecordItem[]
  activeWarnings: WarningNoticeItem[]
  moduleStatus: ModuleStatusItem[]
}

// ========== 预警与模块状态 ==========

export interface WarningNoticeItem {
  warningType: string
  severity: string
  title: string
  content: string
  issuedAt: string
  expiresAt: string
  status: string
  impactArea: string
  defenseGuidance: string
}

export interface ModuleStatusItem {
  code: string
  name: string
  status: string
  progress: number
  description: string
}

// ========== Admin CRUD 类型 ==========

export interface Village {
  id?: number
  code: string
  name: string
  mainCrops: string
  terrain: string
  assistanceLevel: string
}

export interface CropProfile {
  id?: number
  village?: any
  cropName: string
  cropType: string
  plantingArea: string
  growthStage: string
  isMainCrop: boolean
  suitableWeather: string
  riskWeather: string
  riskLevel: number
  farmingNotes: string
}

export interface FarmAdvice {
  id?: number
  village?: any
  title: string
  summary: string
  recommendation: string
  priorityTag: string
  iconKey: string
  priority: number
}

export interface DisasterRecord {
  id?: number
  village?: any
  disasterName: string
  affectedArea: string
  disasterType: string
  triggerWeather: string
  description: string
  remedy: string
  severity: string
}

export interface AssistanceRecord {
  id?: number
  /** 被帮扶农户（手工补录时由后端按 reportId 反查回填） */
  user?: {
    id?: number
    openid?: string
    nickname?: string
    villageCode?: string | null
  } | null
  /** 关联农情上报；自动建档与手工补录都有值，历史手工数据可能为 null */
  reportId?: number | null
  /** 关联上报标题，由后端分页接口批量填充，不落库 */
  reportTitle?: string | null
  assistanceType: string
  description: string
  status: string
  createdAt?: string
  updatedAt?: string
}

// ========== 管理后台通用类型 ==========

export interface AdminUser {
  id: number
  username: string
  realName: string
  phone: string
  email: string
  role: string
  /** 数据范围：村镇管理员绑定的村镇编码，其余角色为 null */
  villageCode: string | null
  status: number
  lastLoginTime: string | null
}

export interface User {
  id: number
  openid: string
  nickname: string
  avatarUrl: string
  villageCode: string
  phone: string
  status: number
  lastLoginTime: string | null
}

export interface Feedback {
  id: number
  userId: number | null
  feedbackType: string
  content: string
  imageUrls: string
  status: string
  reply: string
  createdAt: string
}

export interface OperationLog {
  id: number
  operatorName: string
  operationType: string
  module: string
  description: string
  requestParams: string
  ip: string
  status: number
  createdAt: string
}

export interface FarmReport {
  id: number
  openid: string
  nickname: string
  avatarUrl: string
  villageCode: string
  villageName: string
  reportType: string
  title: string
  description: string
  imageUrls: string
  latitude: number | null
  longitude: number | null
  locationName: string
  cropName: string
  growthStage: string
  status: string
  urgency: string
  createdAt: string
  updatedAt: string
}

export interface FarmResponse {
  id: number
  reportId: number
  responderOpenid: string
  responderName: string
  responderAvatar: string
  responseType: string
  content: string
  createdAt: string
}

// ========== AI Chat 类型 ==========

export interface AiChatMessage {
  role: 'user' | 'assistant'
  content: string
}

export interface AiChatRequest {
  message: string
  villageCode: string
  history?: AiChatMessage[]
}

export interface AiChatResponse {
  reply: string
  villageCode: string
  villageName: string
}

export interface AppConfig {
  id: number
  configKey: string
  configValue: string
  description: string
  category: string
}

// ========== 人工客服实时聊天 ==========

export interface HumanChatSession {
  id: number
  farmerOpenid: string
  farmerNickname: string
  villageCode: string
  villageName: string
  aiContextSnapshot?: string
  adminUsername?: string
  adminRealName?: string
  status: 'WAITING' | 'ACTIVE' | 'CLOSED'
  lastMessage?: string
  lastMessageTime?: string
  adminUnread: number
  farmerUnread: number
  createdAt?: string
  startedAt?: string
  closedAt?: string
}

export interface HumanChatMessage {
  id: number
  sessionId: number
  senderType: 'FARMER' | 'ADMIN'
  senderIdentity: string
  senderName: string
  content: string
  readFlag: boolean
  createdAt: string
  /** 前端渲染标记：同一分钟内的消息只有第一条显示时间 */
  showTime?: boolean
}

export interface WsMessage<T = unknown> {
  type: string
  body?: T
  timestamp: number
}

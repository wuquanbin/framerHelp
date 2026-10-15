import type { ApiResponse, DashboardOverview, VillageOption, AiChatRequest, AiChatResponse } from '@/types/weather'
import { API_BASE } from '@/config/api'

const BASE_URL = API_BASE

async function request<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`)
  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`)
  }

  const payload = (await response.json()) as ApiResponse<T>
  if (!payload.success) {
    throw new Error(payload.message || '接口返回失败')
  }

  return payload.data
}

async function postRequest<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`)
  }

  const payload = (await response.json()) as ApiResponse<T>
  if (!payload.success) {
    throw new Error(payload.message || '接口返回失败')
  }

  return payload.data
}

export function getVillages(): Promise<VillageOption[]> {
  return request<VillageOption[]>('/meta/villages')
}

export function getDashboardOverview(villageCode: string): Promise<DashboardOverview> {
  const query = villageCode ? `?villageCode=${encodeURIComponent(villageCode)}` : ''
  return request<DashboardOverview>(`/dashboard/overview${query}`)
}

export function sendAiChat(params: AiChatRequest): Promise<AiChatResponse> {
  return postRequest<AiChatResponse>('/ai/chat', params)
}

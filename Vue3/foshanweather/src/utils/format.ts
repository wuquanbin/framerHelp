import { FILE_BASE } from '@/config/api'

export function formatDateTime(dateTime: string | null | undefined): string {
  if (!dateTime) {
    return '待更新'
  }

  const date = new Date(dateTime)
  if (Number.isNaN(date.getTime())) {
    return dateTime
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date)
}

export function formatDate(dateText: string): string {
  const date = new Date(dateText)
  if (Number.isNaN(date.getTime())) {
    return dateText
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}

export function splitTags(tags: string): string[] {
  return tags
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)
}

const FILE_BASE_URL = FILE_BASE

export function resolveFileUrl(url: string | null | undefined): string {
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  return FILE_BASE_URL + url
}

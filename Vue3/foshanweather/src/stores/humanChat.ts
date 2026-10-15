import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listHumanChatSessions } from '@/api/admin'
import { adminChatWs } from '@/utils/adminChatWs'

let inited = false
let refreshTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 人工客服徽标状态（侧栏菜单"人工客服工作台"动态提示）：
 * - waitingCount > 0  → 有农户发起转人工等待接单 → 显示 NEW
 * - totalUnread  > 0  → 有未读农户消息 → 显示未读数
 * - 都为 0            → 不显示
 */
export const useHumanChatStore = defineStore('humanChat', () => {
  const waitingCount = ref(0)
  const totalUnread = ref(0)

  async function fetchSummary() {
    try {
      const r = await listHumanChatSessions(undefined)
      waitingCount.value = (r.list || []).filter(s => s.status === 'WAITING').length
      totalUnread.value = r.totalUnread || 0
    } catch {
      // 徽标是辅助信息，拉取失败静默处理
    }
  }

  /** 绑定 WS 事件自动刷新（防抖），幂等，只需调用一次 */
  function init() {
    if (inited) return
    inited = true
    const events = ['NEW_SESSION_NOTIFY', 'NEW_CHAT', 'SESSION_ACCEPTED', 'SESSION_CLOSED', 'SESSION_UNREAD_UPDATE']
    events.forEach(ev =>
      adminChatWs.on(ev, () => {
        if (refreshTimer) clearTimeout(refreshTimer)
        refreshTimer = setTimeout(fetchSummary, 600)
      }),
    )
  }

  return { waitingCount, totalUnread, fetchSummary, init }
})

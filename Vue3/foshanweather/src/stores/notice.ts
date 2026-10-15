import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getAdminStats } from '@/api/admin'

/**
 * 后台待办提醒 Store
 * 统一维护左侧菜单的待处理角标数量：
 * - pendingFeedback：农户新提交、尚未处理的意见反馈
 * - pendingFarmReports：待处理的农情上报
 * AdminLayout 挂载后每 30 秒轮询一次；业务页处理完数据后主动调 fetchCounts() 即时消标。
 */
export const useNoticeStore = defineStore('adminNotice', () => {
  /** 待处理反馈数（反馈管理菜单角标） */
  const pendingFeedback = ref(0)
  /** 待处理农情上报数（农情管理菜单角标） */
  const pendingFarmReports = ref(0)
  let timer: number | null = null

  /** 拉取一次待处理数量 */
  async function fetchCounts() {
    try {
      const stats = await getAdminStats()
      pendingFeedback.value = Number(stats.pendingFeedback || 0)
      pendingFarmReports.value = Number(stats.pendingFarmReports || 0)
    } catch {
      // 静默失败：不打断后台当前操作，下一轮轮询自动重试
    }
  }

  /** 启动定时轮询，先立即拉一次 */
  function startAutoRefresh(intervalMs = 30000) {
    fetchCounts()
    stopAutoRefresh()
    timer = window.setInterval(fetchCounts, intervalMs)
  }

  /** 停止轮询 */
  function stopAutoRefresh() {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  return {
    pendingFeedback,
    pendingFarmReports,
    fetchCounts,
    startAutoRefresh,
    stopAutoRefresh,
  }
})

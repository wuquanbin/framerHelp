import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getVillages, getDashboardOverview } from '@/api/admin'
import type { DashboardOverview } from '@/types/weather'

export type SkyState = 'storm' | 'rain' | 'sunny' | 'alert'

/**
 * 中文天气描述 → 背景天象状态。
 * 只有 4 张底图，所以按「天空长什么样」归并，而不是按字面分类：
 * - 晴 / 多云 / 少云 → sunny（底图是蓝天+积云，正是多云天的样子）
 * - 阴 / 雾 / 霾     → rain（底图是灰蓝阴天，契合；雨丝由 precipitating 单独关闭）
 * - 雨 / 雪          → rain（带雨丝）
 * - 雷 / 暴雨等      → storm
 * 「多云」曾被误归到 rain，导致后台绝大多数时候都停在阴雨画面上，这里纠正。
 */
function classify(weatherType: string): SkyState {
  const s = weatherType || ''
  if (/雷|暴雨|大雨|冰雹|台风|强对流/.test(s)) return 'storm'
  if (/雨|雪|霰/.test(s)) return 'rain'
  if (/阴|雾|霾|浮尘|沙尘/.test(s)) return 'rain'
  return 'sunny'
}

/** 实况是否正在降水。阴天、多云只是云量多，不该有雨丝在飘 */
function isPrecipitating(weatherType: string): boolean {
  return /雨|雪|雷|冰雹|霰/.test(weatherType || '')
}

/**
 * 高危预警只在天象确实会变差时才介入：
 * 红色气象预警 → 暖橙告警态；橙色暴雨/台风/雷电/大风/冰雹 → 雷暴态。
 * 高温、干旱这类「气温类」预警不影响天空面貌，只在预警卡片里呈现，
 * 否则会出现「实况写着晴、背景却是暴雨」的错位。
 */
function skyFromWarnings(overview: DashboardOverview): SkyState | null {
  const active = overview.activeWarnings ?? []
  const severe = active.filter((w) => /红|橙/.test(w.severity || ''))
  if (severe.length === 0) return null

  const weatherHazard = /暴雨|台风|雷电|大风|冰雹|强对流|龙卷/
  if (severe.some((w) => /红/.test(w.severity) && weatherHazard.test(w.warningType || ''))) return 'alert'
  if (severe.some((w) => weatherHazard.test(w.warningType || ''))) return 'storm'
  return null
}

/** 天象优先级，数值越大越「重」 */
const RANK: Record<SkyState, number> = { sunny: 0, rain: 1, storm: 2, alert: 3 }

/** 取两种天象中更恶劣的一个（供后续扩展多数据源时使用） */
export function heaviestSky(a: SkyState, b: SkyState): SkyState {
  return RANK[a] >= RANK[b] ? a : b
}

export const useSkyStore = defineStore('sky', () => {
  const state = ref<SkyState>('storm')
  const villageName = ref('')
  const villageCode = ref('')
  const weatherText = ref('')
  const temperature = ref<number | null>(null)
  const ready = ref(false)

  /** 当前天象对应的村镇；数据概览页切换村镇时会同步更新，保证背景跟着看到的村镇走 */
  const activeCode = ref('')

  /** 实况是否正在降水，用于控制雨丝层是否显示 */
  const precipitating = computed(() => isPrecipitating(weatherText.value))

  /**
   * 用一份已加载的村镇概览更新天象。
   * 独立出来是为了让数据概览页切换村镇时直接复用已有响应，不必再打一次接口。
   *
   * 天象只认实况：保证「大标题写什么天，背景就是什么天」。
   * 今日预报只用于预报条展示，不参与天象，避免实况与背景错位。
   */
  function applyFrom(code: string, overview: DashboardOverview): void {
    const w = overview.currentWeather
    if (!w) return
    activeCode.value = code || w.villageCode || ''
    weatherText.value = w.weatherType
    temperature.value = w.temperature
    villageName.value = w.villageName || overview.village?.name || ''
    villageCode.value = w.villageCode || ''
    state.value = skyFromWarnings(overview) ?? classify(w.weatherType)
    ready.value = true
  }

  async function refresh(): Promise<void> {
    try {
      if (!activeCode.value) {
        const villages = await getVillages()
        const first = villages[0]
        if (first) activeCode.value = first.code
      }
      if (!activeCode.value) return

      const overview = await getDashboardOverview(activeCode.value)
      applyFrom(activeCode.value, overview)
    } catch {
      // 接口不可用时维持默认天象，不阻塞后台使用
    }
  }

  let timer: number | undefined

  function startAutoRefresh(): void {
    refresh()
    if (timer) window.clearInterval(timer)
    timer = window.setInterval(refresh, 10 * 60 * 1000)
  }

  function stopAutoRefresh(): void {
    if (timer) {
      window.clearInterval(timer)
      timer = undefined
    }
  }

  return {
    state,
    villageName,
    villageCode,
    weatherText,
    temperature,
    precipitating,
    ready,
    activeCode,
    applyFrom,
    refresh,
    startAutoRefresh,
    stopAutoRefresh,
  }
})

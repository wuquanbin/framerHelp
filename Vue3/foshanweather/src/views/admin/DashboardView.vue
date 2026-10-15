<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSkyStore } from '@/stores/sky'
import { riskLabel, riskClass } from '@/utils/risk'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
} from 'echarts/components'
import { getAdminStats, getDashboardOverview, getVillages, getCropDistribution } from '@/api/admin'
import { useHumanChatStore } from '@/stores/humanChat'
import type { DashboardOverview, VillageOption } from '@/types/weather'

use([
  CanvasRenderer,
  LineChart,
  PieChart,
  BarChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  DataZoomComponent,
])

const router = useRouter()
const skyStore = useSkyStore()
const humanChat = useHumanChatStore()
const stats = ref<Record<string, number>>({})
const villages = ref<VillageOption[]>([])
const selectedCode = ref('')
const overview = ref<DashboardOverview | null>(null)
const cropDistribution = ref<Array<{ name: string; value: number }>>([])
const loading = ref(true)
const errorText = ref('')

function getLocalDateStr(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function roundTemp(val: number | string | undefined): string {
  if (val === undefined || val === null) return '--'
  return Math.round(Number(val)).toString()
}

// ============ 天气图标（纯路径，不依赖图标库） ============
const CLOUD = 'M7 15.4h10.2a3.4 3.4 0 0 0 .4-6.78 5 5 0 0 0-9.62-1.44A3.9 3.9 0 0 0 7 15.4Z'
const SUN =
  'M15.6 12a3.6 3.6 0 1 1-7.2 0 3.6 3.6 0 0 1 7.2 0' +
  'M12 6.2v2.4M12 15.4v2.4M6.2 12h2.4M15.4 12h2.4' +
  'M7.9 7.9l1.7 1.7M16.1 7.9l-1.7 1.7M7.9 16.1l1.7-1.7M16.1 16.1l-1.7-1.7'

function glyph(weatherType: string): string {
  const s = weatherType || ''
  if (/雷|闪电|强对流/.test(s)) return CLOUD + 'M13.4 17.4 L10.4 21.2 H12.6 L11.6 23.8'
  if (/雪|霰|冰雹|霜/.test(s)) return CLOUD + 'M10.2 17.6v2.6M8.9 18.4h2.6M15.4 17.6v2.6M14.1 18.4h2.6'
  if (/雨/.test(s)) return CLOUD + 'M9.6 17.8l-1.2 3.4M13.2 17.8l-1.2 3.4M16.8 17.8l-1.2 3.4'
  if (/雾|霾|浮尘/.test(s)) return 'M4.5 10.5h15M6 14h12M4.5 17.5h15'
  if (/阴|多云|少云/.test(s)) return CLOUD + SUN.slice(0, 0)
  if (/晴/.test(s)) return SUN
  return CLOUD
}

// ============ 数据装载 ============
onMounted(async () => {
  // 人工客服待办（等待接入 + 未读消息），WS 事件由 store 自动刷新
  humanChat.init()
  humanChat.fetchSummary()
  try {
    const [s, d, cd] = await Promise.all([getAdminStats(), getVillages(), getCropDistribution()])
    stats.value = s
    villages.value = d
    cropDistribution.value = cd
    if (d.length > 0) {
      selectedCode.value = d[0].code
      await loadOverview()
    }
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '数据加载失败'
    console.error('Dashboard init error:', e)
  } finally {
    loading.value = false
  }
})

async function loadOverview() {
  if (!selectedCode.value) return
  errorText.value = ''
  try {
    const data = await getDashboardOverview(selectedCode.value)
    overview.value = data
    // 天象背景跟随当前选中的村镇，避免出现「标题写多云、背景却在暴雨」
    skyStore.applyFrom(selectedCode.value, data)
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '天气数据加载失败'
  }
}

const currentWeather = computed(() => overview.value?.currentWeather ?? null)
const forecastList = computed(() => overview.value?.forecast ?? [])

const villageLabel = computed(() => {
  const w = currentWeather.value
  return w?.villageName || overview.value?.village?.name || '全域农情'
})

/**
 * 实况观测时间文案。
 * 后端每 30 分钟同步一次真实气象数据并落库，所以实况数值是"定时快照"，
 * 会随同步周期变化——把观测时间标出来，避免被误读成随机跳动的模拟数据。
 */
const observedAtText = computed(() => {
  const raw = currentWeather.value?.observationTime
  if (!raw) return ''
  const t = new Date(String(raw).replace(' ', 'T'))
  if (Number.isNaN(t.getTime())) return ''
  const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`
  const dStr = getLocalDateStr(t)
  if (dStr === getLocalDateStr(new Date())) return hm
  if (dStr === getLocalDateStr(new Date(Date.now() - 86400000))) return `昨天 ${hm}`
  return `${t.getMonth() + 1}月${t.getDate()}日 ${hm}`
})

const todayLabel = computed(() => {
  const now = new Date()
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][now.getDay()]
  return `${now.getMonth() + 1}月${now.getDate()}日 · ${week}`
})

const headline = computed(() => {
  const w = currentWeather.value
  if (!w) return { line1: '天象', line2: '数据加载中' }
  // 展示当前村镇今日的最高 / 最低温（取预报第一天）
  const today = forecastList.value[0]
  if (today) {
    const lo = roundTemp(today.lowTemperature)
    const hi = roundTemp(today.highTemperature)
    return {
      line1: w.weatherType,
      line2: lo === hi ? `今日 ${hi}°C` : `今日 ${lo}~${hi}°C`,
    }
  }
  return { line1: w.weatherType, line2: `实时 ${roundTemp(w.temperature)}°C` }
})

const summaryText = computed(() => {
  const w = currentWeather.value
  if (!w) return '正在获取田间气象数据。'
  const advice = overview.value?.farmAdvices?.[0]?.recommendation
  const base = `${villageLabel.value}今日${w.weatherType}，实时气温 ${roundTemp(w.temperature)}°C、体感 ${roundTemp(
    w.apparentTemperature,
  )}°C，相对湿度 ${w.humidity}%，${w.windDirection}${w.windScale}。`
  return advice ? base + advice : base + '暂无针对性农事建议。'
})

// ============ KPI 数字滚动 ============
const kpis = computed(() => [
  { label: '覆盖村镇', value: stats.value.totalVillages ?? stats.value.totalDistricts ?? 0 },
  { label: '作物档案', value: stats.value.totalCrops ?? stats.value.totalPlaces ?? 0 },
  { label: '活跃预警', value: stats.value.activeWarnings ?? 0 },
  { label: '待处理反馈', value: stats.value.pendingFeedback ?? 0 },
])

const kpiDisplay = ref<number[]>([0, 0, 0, 0])

function runCount(index: number, target: number) {
  const start = performance.now()
  const duration = 900
  function step(now: number) {
    const p = Math.min(1, (now - start) / duration)
    const eased = 1 - Math.pow(1 - p, 4)
    kpiDisplay.value[index] = Math.round(target * eased)
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

watch(
  () => kpis.value.map((k) => k.value),
  (vals) => {
    kpiDisplay.value = [0, 0, 0, 0]
    vals.forEach((v, i) => runCount(i, v))
  },
  { immediate: true },
)

// ============ 底部：当前村镇的逐日温度 ============
/**
 * 底部一列 = 未来的一天，显示该天的最高温、天气图标和日期标签。
 * 第一列固定写「今天」，其余用后端返回的 weekLabel（周几）。
 * 跟随右侧下拉框选中的村镇整体切换。
 */
const STRIP_DAYS = 7

const stripItems = computed(() =>
  forecastList.value.slice(0, STRIP_DAYS).map((f, i) => ({
    key: f.forecastDate,
    name: i === 0 ? '今天' : f.weekLabel || f.forecastDate,
    temp: Number(f.highTemperature),
    type: f.weatherType,
    isCurrent: i === 0,
  })),
)
/**
 * ============ 底部实况条的横向滑动 ============
 * 说明文字 / 温度条 / 折线 / 村镇名四层共用同一套栅格，整体放在一条可位移的轨道里。
 * - 村镇少（放得下）：每列均分整宽，轨道宽度 = 容器宽度，不产生滚动；
 * - 村镇多（放不下）：每列收缩到 STRIP_MIN_COL 并允许按住左键左右拖动查看，
 *   两端用渐隐遮罩淡出（叠加淡化），避免出现生硬的裁切边。
 */
const STRIP_PAD = 60 // 左右各内缩 30px，四层共用
const STRIP_MIN_COL = 158 // 单列最小宽度，低于它才转为可滑动

const stripWrapEl = ref<HTMLElement | null>(null)
const stripWrapW = ref(0)
let stripRO: ResizeObserver | null = null

/** 去掉左右内缩后的可用宽度 */
const stripWrapInnerW = computed(() => Math.max(stripWrapW.value - STRIP_PAD, 0))
const stripColW = computed(() => {
  const n = stripItems.value.length || 1
  const avail = stripWrapInnerW.value
  if (!avail) return STRIP_MIN_COL
  return Math.max(STRIP_MIN_COL, avail / n)
})
/** 栅格内容宽度（不含左右内缩） */
const stripInnerW = computed(() => stripColW.value * (stripItems.value.length || 1))
/** 轨道总宽，比内容多出左右内缩 */
const stripTrackW = computed(() => stripInnerW.value + STRIP_PAD)
const stripScrollable = computed(
  () => stripWrapInnerW.value > 0 && stripInnerW.value > stripWrapInnerW.value + 1,
)

const stripGrid = computed(() => ({
  gridTemplateColumns: `repeat(${Math.max(stripItems.value.length, 1)}, ${stripColW.value}px)`,
}))

const stripOffset = ref(0)
const stripDragging = ref(false)
const stripDrag = { active: false, moved: false, startX: 0, startOffset: 0, pointerId: -1 }

/** 允许的最大左移量（负数） */
const stripMaxShiftPx = computed(() => Math.min(0, stripWrapInnerW.value - stripInnerW.value))

/**
 * 两端渐隐只在「该方向确实还藏着内容」时才出现。
 * 否则停在起始位置时，第一列也会被左边缘的渐变白白淡掉。
 */
const stripFadeLeft = computed(() => stripScrollable.value && stripOffset.value < -1)
const stripFadeRight = computed(
  () => stripScrollable.value && stripOffset.value > stripMaxShiftPx.value + 1,
)

function onStripPointerDown(e: PointerEvent) {
  if (!stripScrollable.value || e.button !== 0) return
  const el = stripWrapEl.value
  if (!el) return
  // 只在实况条所在的区域响应拖动，顶部大标题/ KPI 区按下不触发
  const r = el.getBoundingClientRect()
  if (e.clientY < r.top + r.height * 0.55) return
  stripDrag.active = true
  stripDrag.moved = false
  stripDrag.startX = e.clientX
  stripDrag.startOffset = stripOffset.value
  stripDrag.pointerId = e.pointerId
  stripDragging.value = true
}

function onStripPointerMove(e: PointerEvent) {
  if (!stripDrag.active) return
  const dx = e.clientX - stripDrag.startX
  // 超过 4px 才算拖动，避免手抖把点击吃掉
  if (!stripDrag.moved) {
    if (Math.abs(dx) <= 4) return
    stripDrag.moved = true
    // 确认拖动后再捕获指针，否则 click 会落在 wrap 上导致村镇点不动
    try {
      stripWrapEl.value?.setPointerCapture(stripDrag.pointerId)
    } catch {
      /* 部分环境不支持指针捕获，忽略 */
    }
  }
  stripOffset.value = Math.min(0, Math.max(stripMaxShiftPx.value, stripDrag.startOffset + dx))
}

function onStripPointerUp(e: PointerEvent) {
  if (!stripDrag.active) return
  const el = stripWrapEl.value
  if (el && e.pointerId === stripDrag.pointerId) {
    try {
      el.releasePointerCapture(e.pointerId)
    } catch {
      /* 已释放 */
    }
  }
  stripDrag.active = false
  stripDragging.value = false
}

/** 列宽收紧后，把位移夹回合法范围 */
watch([stripInnerW, stripWrapInnerW], () => {
  stripOffset.value = Math.min(0, Math.max(stripMaxShiftPx.value, stripOffset.value))
})

// 轨道宽度依赖容器实测宽度；wrap 带 v-if，挂载时机不确定，所以监听 ref 本身
watch(
  stripWrapEl,
  (el) => {
    stripRO?.disconnect()
    stripRO = null
    if (!el) return
    stripRO = new ResizeObserver((entries) => {
      stripWrapW.value = entries[0]?.contentRect.width ?? 0
    })
    stripRO.observe(el)
  },
  { immediate: true, flush: 'post' },
)

onBeforeUnmount(() => {
  stripRO?.disconnect()
  stripRO = null
})

/**
 * 折线的纵向标尺。
 * 若直接按可见区间的 min/max 拉满，32/33/33/31/32 这种只差 2℃ 的数据会被画成大幅起伏，
 * 视觉上夸大波动。这里强制最小跨度 6℃，如实反映各村温差的同时保证曲线可见。
 */
const MIN_SPAN = 6

function tempDomain(): [number, number] {
  const vals = stripItems.value.map((i) => i.temp)
  if (vals.length === 0) return [0, 1]
  let lo = Math.min(...vals) - 1
  let hi = Math.max(...vals) + 1
  if (hi - lo < MIN_SPAN) {
    const center = (hi + lo) / 2
    lo = center - MIN_SPAN / 2
    hi = center + MIN_SPAN / 2
  }
  return [lo, hi]
}

/** Catmull-Rom → 三次贝塞尔；x 取每列中心，与上下两行的栅格列对齐 */
function smoothPath(values: number[], lo: number, hi: number, w: number): string {
  if (values.length < 2) return ''
  const top = 26
  const bottom = 92
  const span = hi - lo || 1
  const step = w / values.length
  const pts = values.map((v, i) => ({
    x: (i + 0.5) * step,
    y: bottom - ((v - lo) / span) * (bottom - top),
  }))
  let d = `M${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[i - 1] ?? pts[i]
    const p1 = pts[i]
    const p2 = pts[i + 1]
    const p3 = pts[i + 2] ?? p2
    const c1x = p1.x + (p2.x - p0.x) / 6
    const c1y = p1.y + (p2.y - p0.y) / 6
    const c2x = p2.x - (p3.x - p1.x) / 6
    const c2y = p2.y - (p3.y - p1.y) / 6
    d += ` C${c1x.toFixed(1)} ${c1y.toFixed(1)},${c2x.toFixed(1)} ${c2y.toFixed(1)},${p2.x.toFixed(
      1,
    )} ${p2.y.toFixed(1)}`
  }
  return d
}

/** 单条折线：各村实况气温。宽度跟随轨道，村镇多时同样被拖出来 */
const curveMain = computed(() => {
  const [lo, hi] = tempDomain()
  return smoothPath(
    stripItems.value.map((i) => i.temp),
    lo,
    hi,
    stripInnerW.value,
  )
})

/**
 * 折线上的采样圆点。用 HTML 定位而非 SVG circle——
 * SVG 用了 preserveAspectRatio="none"，circle 会被拉成椭圆。
 */
const curveDots = computed(() => {
  const [lo, hi] = tempDomain()
  const span = hi - lo || 1
  // 用像素定位（而非百分比）：轨道宽度会随村镇数量变化，像素坐标才能和 SVG 的
  // viewBox 一一对应，两者不会因为四舍五入而错开。
  const colW = stripColW.value
  return stripItems.value.map((i, idx) => {
    const y = 92 - ((i.temp - lo) / span) * (92 - 26)
    return {
      key: i.key,
      left: `${(idx + 0.5) * colW}px`,
      top: `${(y / 120) * 100}%`,
      isCurrent: i.isCurrent,
    }
  })
})

// ============ 右侧玻璃卡片 ============
const severityRank = (s: string) => (/红/.test(s) ? 4 : /橙/.test(s) ? 3 : /黄/.test(s) ? 2 : 1)

const topCrop = computed(() => overview.value?.cropProfiles?.[0] ?? null)
const topAdvice = computed(() => overview.value?.farmAdvices?.[0] ?? null)
const topWarning = computed(() => {
  const list = overview.value?.activeWarnings ?? []
  return [...list].sort((a, b) => severityRank(b.severity) - severityRank(a.severity))[0] ?? null
})

/** 农事建议优先级（数字）→ 标签配色。作物风险等级的映射已抽到 @/utils/risk */
function adviceClass(priority: number): string {
  if (priority >= 4) return 'db-lv-high'
  if (priority === 3) return 'db-lv-mid'
  return 'db-lv-low'
}

/** 预警状态英文枚举 → 中文 */
function warningStatus(status: string): string {
  const map: Record<string, string> = { active: '生效中', expired: '已失效', canceled: '已解除', pending: '待发布' }
  return map[(status || '').toLowerCase()] || '生效中'
}

const pendingFeedbackCount = computed(() => stats.value.pendingFeedback ?? 0)
const pendingReportCount = computed(() => stats.value.pendingFarmReports ?? 0)

// 待办卡聚合：农情上报、用户反馈、人工客服三处待办，点击直达对应处理页
const todoItems = computed(() => {
  const waiting = humanChat.waitingCount
  const unread = humanChat.totalUnread
  const chatSub =
    waiting > 0
      ? `${waiting} 个会话等待接入${unread > 0 ? ` · ${unread} 条未读` : ''}`
      : unread > 0
        ? `${unread} 条农户消息未读`
        : '暂无等待接入的会话'
  return [
    {
      key: 'report',
      label: '待处理农情上报',
      count: pendingReportCount.value,
      route: '/admin/farm-reports?status=pending',
      urgent: pendingReportCount.value > 0,
      sub: pendingReportCount.value > 0 ? '待核实、处置与登记台账' : '暂无待处理上报',
    },
    {
      key: 'feedback',
      label: '待处理用户反馈',
      count: pendingFeedbackCount.value,
      route: '/admin/feedback?status=pending',
      urgent: pendingFeedbackCount.value > 0,
      sub: pendingFeedbackCount.value > 0 ? '待回复或标记解决' : '暂无待处理反馈',
    },
    {
      key: 'human-chat',
      label: '人工客服',
      count: waiting + unread,
      route: '/admin/human-chat',
      urgent: waiting > 0,
      sub: chatSub,
    },
  ]
})
const todoTotal = computed(() => todoItems.value.reduce((sum, t) => sum + t.count, 0))

// ============ 图表配置 ============
const tempTrendOption = computed(() => {
  const days = forecastList.value
  const today = new Date()
  const todayStr = getLocalDateStr(today)
  const yesterdayStr = getLocalDateStr(new Date(today.getTime() - 86400000))
  const todayIndex = days.findIndex((d) => d.forecastDate === todayStr)
  const weekLabels = days.map((d) =>
    d.forecastDate === todayStr
      ? '今天'
      : d.forecastDate === yesterdayStr
        ? '昨天'
        : d.weekLabel.replace(/星期/g, '周'),
  )
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#dbe6dd',
      textStyle: { color: '#2b3a32', fontSize: 12 },
      axisPointer: { type: 'cross' },
    },
    legend: { bottom: 0, itemWidth: 16, itemHeight: 8, textStyle: { fontSize: 11, color: '#7d8b84' } },
    grid: { top: 40, right: 18, bottom: 46, left: 46 },
    xAxis: {
      type: 'category',
      data: weekLabels,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { fontSize: 11, color: '#7d8b84', margin: 12 },
    },
    yAxis: {
      type: 'value',
      name: '温度(°C)',
      nameLocation: 'middle',
      nameGap: 32,
      nameTextStyle: { fontSize: 11, color: '#7d8b84' },
      splitLine: { lineStyle: { color: '#eef2ee', type: 'dashed' } },
      axisLabel: { fontSize: 11, color: '#7d8b84' },
    },
    series: [
      {
        name: '最高温',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: (v: number, p: any) => (p.dataIndex === todayIndex ? 9 : 5),
        data: days.map((d) => d.highTemperature),
        lineStyle: { width: 2.4, color: '#d0544a' },
        itemStyle: { color: '#d0544a', borderColor: '#fff', borderWidth: 2 },
        label: { show: true, position: 'top', distance: 9, fontSize: 11, color: '#d0544a', formatter: '{c}°' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(208,84,74,0.22)' },
              { offset: 1, color: 'rgba(208,84,74,0.02)' },
            ],
          },
        },
      },
      {
        name: '最低温',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: (v: number, p: any) => (p.dataIndex === todayIndex ? 9 : 5),
        data: days.map((d) => d.lowTemperature),
        lineStyle: { width: 2.4, color: '#3f7f52' },
        itemStyle: { color: '#3f7f52', borderColor: '#fff', borderWidth: 2 },
        label: { show: true, position: 'bottom', distance: 9, fontSize: 11, color: '#3f7f52', formatter: '{c}°' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(63,127,82,0.20)' },
              { offset: 1, color: 'rgba(63,127,82,0.02)' },
            ],
          },
        },
      },
    ],
  }
})

const rainBarOption = computed(() => {
  const days = forecastList.value
  const todayStr = getLocalDateStr(new Date())
  const todayIndex = days.findIndex((d) => d.forecastDate === todayStr)
  return {
    tooltip: {
      trigger: 'axis',
      formatter: '{b}: {c}%',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#dbe6dd',
      textStyle: { color: '#2b3a32', fontSize: 12 },
    },
    grid: { top: 20, right: 18, bottom: 40, left: 46 },
    xAxis: {
      type: 'category',
      data: days.map((d) => (d.weekLabel || '').replace(/星期/g, '周')),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { fontSize: 11, color: '#7d8b84', margin: 12 },
    },
    yAxis: {
      type: 'value',
      max: 100,
      splitLine: { lineStyle: { color: '#eef2ee', type: 'dashed' } },
      axisLabel: { fontSize: 11, color: '#7d8b84' },
    },
    series: [
      {
        type: 'bar',
        barWidth: '42%',
        data: days.map((d, i) => ({
          value: d.precipitationProbability || 0,
          itemStyle: {
            borderRadius: [5, 5, 0, 0],
            color:
              i === todayIndex
                ? d.precipitationProbability > 50
                  ? '#d0544a'
                  : '#d98324'
                : d.precipitationProbability > 50
                  ? '#4f8fb5'
                  : '#6ba376',
          },
        })),
      },
    ],
  }
})

const cropPieOption = computed(() => {
  const data = cropDistribution.value || []
  const colors = ['#3f7f52', '#6ba376', '#9dc4a6', '#c2dcbf', '#d98324', '#4f8fb5', '#8a9f6d']
  return {
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c}亩 ({d}%)',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#dbe6dd',
      textStyle: { color: '#2b3a32', fontSize: 12 },
    },
    legend: { orient: 'vertical', right: '4%', top: 'center', itemWidth: 11, itemHeight: 11, textStyle: { fontSize: 12, color: '#46554d' } },
    series: [
      {
        name: '作物类型',
        type: 'pie',
        radius: ['44%', '68%'],
        center: ['36%', '50%'],
        avoidLabelOverlap: true,
        padAngle: 2,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        emphasis: { label: { show: true, fontWeight: 'bold', fontSize: 13 } },
        data:
          data.length > 0
            ? data.map((it, i) => ({ name: it.name, value: it.value, itemStyle: { color: colors[i % colors.length] } }))
            : [{ name: '暂无数据', value: 1, itemStyle: { color: '#e4e7ed' } }],
      },
    ],
  }
})

const disasterBarOption = computed(() => {
  const disasters = overview.value?.disasterRecords ?? []
  const counts: Record<number, number> = { 1: 0, 2: 0, 3: 0, 4: 0 }
  for (const d of disasters) {
    const n = Number(d.severity) || 1
    counts[n] = (counts[n] ?? 0) + 1
  }
  const levels = [
    { label: '一般', value: counts[1], color: '#6ba376' },
    { label: '中等', value: counts[2], color: '#d98324' },
    { label: '严重', value: counts[3], color: '#d0544a' },
    { label: '特大', value: counts[4], color: '#97372f' },
  ]
  return {
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(255,255,255,0.96)',
      borderColor: '#dbe6dd',
      textStyle: { color: '#2b3a32', fontSize: 12 },
    },
    grid: { top: 10, right: 20, bottom: 32, left: 56 },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#eef2ee', type: 'dashed' } },
      axisLabel: { fontSize: 11, color: '#7d8b84' },
    },
    yAxis: {
      type: 'category',
      data: levels.map((l) => l.label),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { fontSize: 12, color: '#46554d' },
    },
    series: [
      {
        type: 'bar',
        barWidth: 20,
        data: levels.map((l) => ({ value: l.value, itemStyle: { color: l.color, borderRadius: [0, 5, 5, 0] } })),
      },
    ],
  }
})
</script>

<template>
  <div class="db" v-loading="loading">
    <!-- ============ 沉浸式天象屏 ============ -->
    <section class="db-hero">
      <span class="db-chip"><i class="db-dot"></i>{{ villageLabel }} · 农情气象预报</span>

      <div class="db-main">
        <span class="db-eyebrow">{{ todayLabel }}</span>
        <h1 class="db-title">
          <em>{{ headline.line1 }}</em>
          <em>{{ headline.line2 }}</em>
        </h1>
        <p class="db-desc">{{ summaryText }}</p>

        <div class="db-kpi">
          <div v-for="(k, i) in kpis" :key="k.label" class="db-kpi-item">
            <b>{{ kpiDisplay[i] }}</b>
            <span>{{ k.label }}</span>
          </div>
        </div>
      </div>

      <!-- 底部：当前村镇未来几天的温度走势，跟随右侧下拉框切换 -->
      <div v-if="stripItems.length" class="db-strip-cap">
        {{ villageLabel }} · 未来 {{ stripItems.length }} 天
      </div>
      <div
        v-if="stripItems.length"
        ref="stripWrapEl"
        class="db-strip-wrap"
        :class="{
          'is-scrollable': stripScrollable,
          'is-dragging': stripDragging,
          'fade-l': stripFadeLeft,
          'fade-r': stripFadeRight,
        }"
        @pointerdown="onStripPointerDown"
        @pointermove="onStripPointerMove"
        @pointerup="onStripPointerUp"
        @pointercancel="onStripPointerUp"
      >
        <div
          class="db-strip-track"
          :style="{ width: stripTrackW + 'px', transform: `translate3d(${stripOffset}px, 0, 0)` }"
        >
          <div class="db-forecast" :style="stripGrid">
            <div v-for="d in stripItems" :key="d.key" class="db-fc" :class="{ on: d.isCurrent }">
              <b>{{ roundTemp(d.temp) }}°</b>
              <svg viewBox="0 0 24 24" class="db-glyph">
                <path :d="glyph(d.type)" />
              </svg>
            </div>
          </div>

          <svg
            v-if="curveMain"
            class="db-curve"
            :viewBox="`0 0 ${stripInnerW} 120`"
            preserveAspectRatio="none"
          >
            <path class="db-curve-a" :d="curveMain" pathLength="1000" />
          </svg>
          <div v-if="curveDots.length" class="db-dots">
            <i
              v-for="p in curveDots"
              :key="p.key"
              class="db-dot-p"
              :class="{ on: p.isCurrent }"
              :style="{ left: p.left, top: p.top }"
            />
          </div>

          <div class="db-week" :style="stripGrid">
            <span v-for="w in stripItems" :key="w.key" :class="{ on: w.isCurrent }">{{ w.name }}</span>
          </div>
        </div>
      </div>

      <!-- 右侧玻璃卡片 -->
      <div class="db-side">
        <div class="db-glass db-now">
          <div class="db-now-head">
            <svg viewBox="0 0 24 24" class="db-pin">
              <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11z" />
              <path d="M12 7.6a2.4 2.4 0 1 1 0 4.8 2.4 2.4 0 0 1 0-4.8z" />
            </svg>
            <el-select
              v-model="selectedCode"
              size="small"
              class="db-select"
              popper-class="db-select-popper"
              filterable
              :reserve-keyword="false"
              placeholder="搜索村镇"
              @change="loadOverview"
            >
              <el-option v-for="v in villages" :key="v.code" :label="v.name" :value="v.code" />
            </el-select>
          </div>

          <div class="db-temp">
            <b>{{ roundTemp(currentWeather?.temperature) }}</b>
            <sup>°C</sup>
          </div>
          <div class="db-now-type">{{ currentWeather?.weatherType || '--' }}</div>

          <div class="db-metrics">
            <span>湿度 {{ currentWeather?.humidity ?? '--' }}%</span>
            <span>{{ currentWeather?.windDirection || '--' }}{{ currentWeather?.windScale || '' }}</span>
            <span>降水 {{ currentWeather?.precipitationProbability ?? '--' }}%</span>
          </div>

          <!-- 观测时间戳：实况是每 30 分钟同步一次的定时快照，标注出来才能解释"数值会变" -->
          <div v-if="observedAtText" class="db-observed">实况观测 {{ observedAtText }} · 每 30 分钟同步</div>
        </div>

        <div v-if="topCrop" class="db-glass db-mini">
          <div class="db-mini-txt">
            <small>主要作物</small>
            <b>{{ topCrop.cropName }}</b>
            <em>{{ topCrop.growthStage }} · {{ topCrop.plantingArea }}</em>
          </div>
          <span class="db-mini-val db-val-tag" :class="riskClass(topCrop.riskLevel)">{{
            riskLabel(topCrop.riskLevel)
          }}</span>
        </div>

        <div v-if="topAdvice" class="db-glass db-mini">
          <div class="db-mini-txt">
            <small>头条农事建议</small>
            <b>{{ topAdvice.title }}</b>
            <em>{{ topAdvice.summary }}</em>
          </div>
          <span class="db-mini-val db-val-tag" :class="adviceClass(topAdvice.priority)">{{
            topAdvice.priorityTag || '常规'
          }}</span>
        </div>

        <div v-if="topWarning" class="db-glass db-mini db-mini-warn">
          <div class="db-mini-txt">
            <small>{{ topWarning.severity }}{{ topWarning.warningType }}预警</small>
            <b>{{ topWarning.title }}</b>
            <em>{{ topWarning.impactArea }}</em>
          </div>
          <span class="db-mini-val db-val-alert">{{ warningStatus(topWarning.status) }}</span>
        </div>
      </div>
    </section>

    <!-- ============ 明细数据 ============ -->
    <section class="db-data">
      <p v-if="errorText" class="db-error">{{ errorText }}</p>

      <div class="db-grid">
        <div class="db-card">
          <header><h3>7日气温趋势</h3></header>
          <v-chart v-if="forecastList.length" :option="tempTrendOption" autoresize style="height: 240px" />
          <p v-else class="db-empty">暂无预报数据</p>
        </div>

        <div class="db-card">
          <header><h3>降水概率预报</h3></header>
          <v-chart v-if="forecastList.length" :option="rainBarOption" autoresize style="height: 240px" />
          <p v-else class="db-empty">暂无预报数据</p>
        </div>

        <div class="db-card">
          <header><h3>作物类型面积分布</h3></header>
          <v-chart :option="cropPieOption" autoresize style="height: 240px" />
        </div>

        <div class="db-card">
          <header><h3>灾害记录分布</h3></header>
          <v-chart :option="disasterBarOption" autoresize style="height: 240px" />
        </div>
      </div>

      <div class="db-grid db-grid-2">
        <div class="db-card">
          <header>
            <h3>待办事项</h3>
            <span v-if="todoTotal === 0" class="db-todo-done">全部已处理</span>
          </header>
          <button
            v-for="t in todoItems"
            :key="t.key"
            class="db-todo"
            :class="{ urgent: t.urgent, done: t.count === 0 }"
            @click="router.push(t.route)"
          >
            <b>{{ t.count }}</b>
            <span class="db-todo-txt">
              <em>{{ t.label }}</em>
              <small>{{ t.sub }}</small>
            </span>
            <i>›</i>
          </button>
        </div>

        <div class="db-card">
          <header>
            <h3>农事建议</h3>
            <button class="db-link" @click="router.push('/admin/farm-advices')">查看全部</button>
          </header>
          <p v-if="!overview?.farmAdvices?.length" class="db-empty">暂无农事建议</p>
          <div v-for="a in overview?.farmAdvices ?? []" :key="a.title" class="db-advice">
            <span class="db-pill" :class="adviceClass(a.priority)">
              {{ a.priorityTag }}
            </span>
            <div>
              <b>{{ a.title }}</b>
              <em>{{ a.summary }}</em>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.db {
  position: relative;
}

/* ============ 沉浸式天象屏 ============ */
.db-hero {
  position: relative;
  min-height: calc(100vh - 24px);
  min-height: calc(100dvh - 24px);
  padding: 30px 28px 40px;
  color: var(--ad-accent);
}

.db-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: 999px;
  font-size: 12.5px;
  color: var(--ad-accent);
  background: var(--ad-shield);
  border: 1px solid var(--ad-gbd);
  backdrop-filter: blur(16px);
}
.db-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: db-breathe 2.2s ease-in-out infinite;
}
@keyframes db-breathe {
  0%,
  100% {
    opacity: 0.4;
  }
  50% {
    opacity: 1;
  }
}

.db-main {
  margin-top: clamp(40px, 9vh, 92px);
  max-width: min(52ch, 56%);
}
.db-eyebrow {
  font-size: 12.5px;
  letter-spacing: 0.08em;
  color: var(--ad-accent-dim);
}
.db-title {
  margin: 14px 0 0;
  font-size: clamp(34px, 4.2vw, 60px);
  line-height: 1.06;
  font-weight: 300;
  letter-spacing: -0.02em;
  text-shadow: 0 6px 34px rgba(0, 0, 0, 0.4);
}
.db-title em {
  display: block;
  font-style: normal;
}
.db-title em:last-child {
  font-size: 0.52em;
  opacity: 0.82;
  letter-spacing: -0.01em;
}
.db-desc {
  margin: 18px 0 0;
  max-width: 46ch;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--ad-accent-dim);
  text-shadow: 0 2px 16px rgba(0, 0, 0, 0.45);
}

.db-kpi {
  display: flex;
  flex-wrap: wrap;
  gap: 30px;
  margin-top: 30px;
}
.db-kpi-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.db-kpi-item b {
  font-size: 30px;
  font-weight: 300;
  letter-spacing: -0.02em;
  line-height: 1;
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.db-kpi-item span {
  font-size: 12px;
  color: var(--ad-accent-dim);
}

/* 说明文字 + 温度条 + 折线 + 村镇名，四者共用同一套栅格，逐列对齐 */
.db-strip-cap {
  position: absolute;
  left: 30px;
  bottom: 34.5%;
  font-size: 11.5px;
  letter-spacing: 0.08em;
  color: var(--ad-accent-dim);
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.5);
}

/* 拖动裁剪层：铺满整页（内部各层的百分比定位依旧相对整页高度，布局不变），
   只做横向裁剪，村镇多到放不下时可以左右拖出来看 */
.db-strip-wrap {
  position: absolute;
  inset: 0;
  overflow: hidden;
  touch-action: pan-y;
  /* 拖动时不选中文字 */
  user-select: none;
}
/* 只有村镇多到放不下时才显示抓手光标，放得下时保持默认光标 */
.db-strip-wrap.is-scrollable {
  cursor: grab;
}
/* 两端渐隐（叠加淡化）。只在「该方向还有内容没露出来」时才挂上 ——
   否则停在起始位置时，第一列会被左边缘的渐变白白淡掉。 */
.db-strip-wrap.fade-l {
  mask-image: linear-gradient(90deg, transparent 0, #000 34px, #000 100%);
}
.db-strip-wrap.fade-r {
  mask-image: linear-gradient(90deg, #000 0, #000 calc(100% - 34px), transparent 100%);
}
.db-strip-wrap.fade-l.fade-r {
  mask-image: linear-gradient(
    90deg,
    transparent 0,
    #000 34px,
    #000 calc(100% - 34px),
    transparent 100%
  );
}
.db-strip-wrap.is-dragging {
  cursor: grabbing;
}
.db-strip-track {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  transition: transform 0.42s cubic-bezier(0.16, 1, 0.3, 1);
}
.db-strip-wrap.is-dragging .db-strip-track {
  transition: none;
}

.db-forecast {
  position: absolute;
  left: 30px;
  right: 30px;
  bottom: 27%;
  display: grid;
  justify-items: center;
  align-items: flex-end;
}
.db-fc {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 4px 6px;
  color: var(--ad-accent);
  opacity: 0.66;
  transition: 0.4s cubic-bezier(0.16, 1, 0.3, 1);
  text-shadow: 0 2px 14px rgba(0, 0, 0, 0.5);
}
.db-fc.on {
  opacity: 1;
  transform: translateY(-5px);
}
.db-fc b {
  font-size: 25px;
  font-weight: 300;
  letter-spacing: -0.02em;
}
.db-glyph {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

/* 温度曲线：与上方栅格同宽，采样点落在每列中心 */
.db-curve {
  position: absolute;
  left: 30px;
  /* SVG 是替换元素：只写 left/right + width:auto 时，浏览器不会按 left/right 撑开它，
     而是拿 viewBox 的宽高比（1440×120 = 12:1）配 height:88px 反推宽度 → 只有 1056px，
     比栅格窄 260px。折线被横向压缩，而圆点按 100% 分布，两者必然错位。
     必须显式给宽度，且与 .db-dots 用同一套算式。 */
  width: calc(100% - 60px);
  bottom: 17%;
  height: 88px;
  pointer-events: none;
}
.db-curve-a {
  fill: none;
  stroke: rgba(255, 255, 255, 0.86);
  stroke-width: 1.8;
  stroke-linecap: round;
  /* 路径带 pathLength="1000"，dash 长度按归一化后的总长算，
     这样轨道变宽（村镇变多）时线也不会画不全 */
  stroke-dasharray: 1000;
  filter: drop-shadow(0 0 8px rgba(255, 255, 255, 0.55));
  animation: db-draw 1.8s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}

/* 折线采样点：与 .db-curve 同区域，用 HTML 定位避免被拉伸成椭圆 */
.db-dots {
  position: absolute;
  left: 30px;
  right: 30px;
  bottom: 17%;
  height: 88px;
  pointer-events: none;
}
.db-dot-p {
  position: absolute;
  width: 7px;
  height: 7px;
  margin: -3.5px 0 0 -3.5px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 0 10px rgba(255, 255, 255, 0.6);
  opacity: 0;
  animation: db-pop 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards;
}
.db-dot-p.on {
  width: 11px;
  height: 11px;
  margin: -5.5px 0 0 -5.5px;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(255, 255, 255, 0.22), 0 0 12px rgba(255, 255, 255, 0.8);
}
@keyframes db-pop {
  from {
    opacity: 0;
    transform: scale(0.4);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes db-draw {
  from {
    stroke-dashoffset: 1000;
  }
  to {
    stroke-dashoffset: 0;
  }
}

/* 星期条 */
.db-week {
  position: absolute;
  left: 30px;
  right: 30px;
  bottom: 4.5%;
  display: grid;
  justify-items: center;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.6);
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.55);
}
.db-week span {
  padding: 5px 10px;
  position: relative;
}
.db-week span.on {
  color: #fff;
  font-weight: 500;
}
.db-week span.on::after {
  content: '';
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 0;
  height: 1.5px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.9);
}

/* 右侧玻璃卡片 */
.db-side {
  position: absolute;
  right: 26px;
  top: 26px;
  width: 272px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.db-glass {
  border-radius: 18px;
  border: 1px solid var(--ad-gbd);
  background: linear-gradient(160deg, var(--ad-g1), var(--ad-g2));
  backdrop-filter: blur(28px) saturate(170%);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.3), inset 0 1px 0 rgba(255, 255, 255, 0.42);
  color: var(--ad-accent);
  animation: db-rise 0.75s cubic-bezier(0.16, 1, 0.3, 1) both;
}
.db-side > .db-glass:nth-child(2) {
  animation-delay: 0.08s;
}
.db-side > .db-glass:nth-child(3) {
  animation-delay: 0.16s;
}
.db-side > .db-glass:nth-child(4) {
  animation-delay: 0.24s;
}
@keyframes db-rise {
  from {
    opacity: 0;
    transform: translateX(18px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.db-now {
  padding: 14px 16px 16px;
}
.db-now-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.db-pin {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
}
.db-select {
  flex: 1;
  min-width: 0;
}
.db-select :deep(.el-select__wrapper) {
  background: rgba(12, 24, 20, 0.28);
  box-shadow: none;
  border-radius: 9px;
  color: var(--ad-accent);
  min-height: 26px;
  transition: background 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}
.db-select :deep(.el-select__wrapper.is-focused) {
  background: rgba(255, 255, 255, 0.12);
  box-shadow: 0 0 0 1px var(--ad-gbd) inset, 0 0 0 1px rgba(255, 255, 255, 0.14);
}
.db-select :deep(.el-select__wrapper.is-hovering:not(.is-focused)) {
  background: rgba(255, 255, 255, 0.09);
}
.db-select :deep(.el-select__placeholder),
.db-select :deep(.el-select__selected-item),
.db-select :deep(.el-select__caret) {
  color: var(--ad-accent);
}
/* 下拉展开后，选中文字会变成可编辑的过滤输入框 */
.db-select :deep(.el-select__selection-input) {
  color: var(--ad-accent);
  caret-color: var(--ad-accent);
  font-size: 13px;
}
.db-select :deep(.el-select__selection-input::placeholder) {
  color: var(--ad-accent);
  opacity: 0.55;
}

.db-temp {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin: 6px 0 2px;
}
.db-temp b {
  font-size: 54px;
  font-weight: 300;
  line-height: 1;
  letter-spacing: -0.04em;
}
.db-temp sup {
  font-size: 21px;
  font-weight: 300;
  margin-top: 6px;
  opacity: 0.66;
}
.db-now-type {
  font-size: 13px;
  color: var(--ad-accent-dim);
}
.db-metrics {
  display: flex;
  gap: 14px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ad-gbd);
  font-size: 11.5px;
  color: var(--ad-accent-dim);
}

.db-observed {
  margin-top: 8px;
  font-size: 11px;
  letter-spacing: 0.02em;
  color: var(--ad-accent-dim);
  opacity: 0.82;
}

.db-mini {
  padding: 12px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.db-mini-txt {
  flex: 1;
  min-width: 0;
}
.db-mini-txt small {
  display: block;
  font-size: 10.5px;
  opacity: 0.72;
  margin-bottom: 4px;
}
.db-mini-txt b {
  display: block;
  font-size: 13.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.db-mini-txt em {
  display: block;
  font-style: normal;
  font-size: 11px;
  opacity: 0.74;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.db-mini-val {
  flex: 0 0 auto;
  font-size: 18px;
  font-weight: 300;
  opacity: 0.9;
}
.db-val-tag {
  font-size: 11px;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.2);
  font-weight: 600;
  opacity: 1;
  color: #fff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  letter-spacing: 0.02em;
}
.db-val-tag.db-lv-high {
  background: rgba(226, 96, 78, 0.34);
}
.db-val-tag.db-lv-mid {
  background: rgba(233, 160, 62, 0.34);
}
.db-val-tag.db-lv-low {
  background: rgba(120, 200, 150, 0.3);
}
.db-mini-warn {
  border-color: rgba(255, 168, 96, 0.5);
}
.db-val-alert {
  font-size: 11px;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(240, 150, 70, 0.32);
  font-weight: 600;
  opacity: 1;
  color: #fff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  letter-spacing: 0.02em;
}

/* ============ 明细数据 ============ */
.db-data {
  position: relative;
  padding: 24px 28px 28px;
  background: rgba(250, 251, 250, 0.9);
  backdrop-filter: blur(22px) saturate(140%);
  border-radius: 20px 20px 0 0;
}
.db-error {
  margin: 0 0 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdecec;
  border: 1px solid #f6cfcf;
  color: #a83b34;
  font-size: 13px;
}
.db-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}
.db-grid-2 {
  margin-bottom: 0;
}
.db-card {
  background: #fff;
  border: 1px solid rgba(60, 92, 72, 0.1);
  border-radius: 14px;
  padding: 16px 18px;
}
.db-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.db-card h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 500;
  color: #2b3a32;
}
.db-link {
  border: 0;
  background: none;
  font: inherit;
  font-size: 12.5px;
  color: #3f7f52;
  cursor: pointer;
}
.db-link:hover {
  text-decoration: underline;
}
.db-empty {
  margin: 0;
  padding: 24px 0;
  text-align: center;
  color: #a3b0a8;
  font-size: 13px;
}

.db-todo {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 12px 14px;
  margin-bottom: 10px;
  border: 1px solid rgba(60, 92, 72, 0.1);
  border-radius: 11px;
  background: #f7faf8;
  cursor: pointer;
  text-align: left;
  font: inherit;
  transition: 0.24s;
}
.db-todo:hover {
  background: #eef6f1;
}
.db-todo.urgent {
  background: #fdf1ef;
  border-color: rgba(208, 84, 74, 0.24);
}
.db-todo.done {
  background: transparent;
  border-color: #f0f4f1;
  cursor: pointer;
  opacity: 0.72;
}
.db-todo b {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 500;
  background: #eef2ee;
  color: #64706a;
}
.db-todo.urgent b {
  background: #d0544a;
  color: #fff;
}
.db-todo.done b {
  background: #f2f5f3;
  color: #9aa8a0;
}
.db-todo-txt {
  flex: 1;
  min-width: 0;
}
.db-todo-txt em {
  display: block;
  font-style: normal;
  font-size: 14px;
  color: #2b3a32;
}
.db-todo-txt small {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #8a978f;
}
.db-todo.done .db-todo-txt em {
  color: #7d8b84;
}
.db-todo i {
  font-style: normal;
  font-size: 18px;
  color: #b6c1ba;
}
.db-todo-done {
  font-size: 12px;
  color: #3f7f52;
  background: #edf6ef;
  padding: 3px 10px;
  border-radius: 999px;
}

.db-advice {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f4f1;
}
.db-advice:last-child {
  border-bottom: 0;
}
.db-advice b {
  display: block;
  font-size: 13.5px;
  font-weight: 500;
  color: #2b3a32;
}
.db-advice em {
  display: block;
  font-style: normal;
  font-size: 12px;
  color: #7d8b84;
  margin-top: 2px;
}
.db-pill {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 11px;
}
.db-pill.db-lv-high {
  background: #fdecec;
  color: #a83b34;
}
.db-pill.db-lv-mid {
  background: #fdf3e3;
  color: #96601a;
}
.db-pill.db-lv-low {
  background: #edf6ef;
  color: #3f7f52;
}

@media (max-width: 1180px) {
  .db-side {
    display: none;
  }
  .db-main {
    max-width: 100%;
  }
  .db-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
/*
 * 村镇下拉面板（teleport 到 body，只能用全局样式 + popper-class 命中）。
 * 深色玻璃拟态，与首页玻璃卡片同一语言：
 * 深绿玻璃底 + 浅色文字 + 细白描边，配色对四种天象主题都成立。
 */
.db-select-popper.el-popper {
  background: rgba(11, 24, 19, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 12px;
  backdrop-filter: blur(24px) saturate(160%);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.14);
}
.db-select-popper .el-popper__arrow::before {
  background: rgba(11, 24, 19, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.16);
}
.db-select-popper .el-select-dropdown__item {
  color: rgba(236, 245, 239, 0.72);
  border-radius: 8px;
  margin: 2px 6px;
  padding: 0 10px;
  transition: background 0.25s cubic-bezier(0.16, 1, 0.3, 1), color 0.25s;
}
.db-select-popper .el-select-dropdown__item.is-hovering,
.db-select-popper .el-select-dropdown__item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.db-select-popper .el-select-dropdown__item.is-selected {
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  font-weight: 600;
}
.db-select-popper .el-select-dropdown__item.is-disabled {
  color: rgba(236, 245, 239, 0.32);
}
.db-select-popper .el-select-dropdown__empty {
  color: rgba(236, 245, 239, 0.48);
  padding: 14px 0;
}
.db-select-popper .el-scrollbar__thumb {
  background: rgba(255, 255, 255, 0.22);
}
</style>

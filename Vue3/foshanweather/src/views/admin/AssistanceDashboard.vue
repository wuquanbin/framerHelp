<script setup lang="ts">
import { ref, computed, onMounted, type Component } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Download,
  Refresh,
  Aim,
  FirstAidKit,
  Warning,
  Sunny,
  ChatDotRound,
  Box,
  Connection,
} from '@element-plus/icons-vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { LineChart, PieChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
} from 'echarts/components'
import { getAssistanceDashboard, exportAssistanceExcel } from '@/api/admin'
import type { AssistanceDashboard, AssistanceRecentItem } from '@/api/admin'

use([
  CanvasRenderer,
  LineChart,
  PieChart,
  TitleComponent,
  TooltipComponent,
  LegendComponent,
  GridComponent,
])

/* ---------------- 数据加载 ---------------- */

const loading = ref(false)
const errorText = ref('')
const exporting = ref(false)
const data = ref<AssistanceDashboard | null>(null)

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    data.value = await getAssistanceDashboard()
  } catch (e: any) {
    errorText.value = e?.response?.data?.message || e?.message || '聚合服务暂时不可用，请稍后重试'
    ElMessage.error('帮扶看板数据加载失败')
  } finally {
    loading.value = false
  }
}

async function handleExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    await exportAssistanceExcel()
    ElMessage.success('帮扶成效 Excel 报表已导出')
  } catch {
    ElMessage.error('导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

/* ---------------- 视觉映射 ---------------- */

const TYPE_META: Record<string, { color: string; icon: Component }> = {
  病虫害防治: { color: '#b0452b', icon: Aim },
  灾损救助: { color: '#c07d16', icon: FirstAidKit },
  长势指导: { color: '#2d6a4f', icon: Sunny },
  诉求帮扶: { color: '#3f6fd0', icon: ChatDotRound },
  物资帮扶: { color: '#8a6d3b', icon: Box },
  综合帮扶: { color: '#5f9e78', icon: Connection },
}
function typeMeta(type: string) {
  return TYPE_META[type] ?? { color: '#5f9e78', icon: Connection }
}

function statusClass(status: string | null | undefined) {
  if (status === '已完成') return 'st-done'
  if (status === '进行中') return 'st-doing'
  return 'st-pending'
}

/* ---------------- 展示辅助 ---------------- */

const round1 = (n: number) => Math.round(n * 10) / 10

function hourText(h: number | null | undefined): string {
  if (h == null) return '--'
  if (h < 1) return `${Math.round(h * 60)} 分钟`
  if (h < 48) return `${round1(h)} 小时`
  return `${round1(h / 24)} 天`
}

function responseHoursText(h: number) {
  if (h < 1) return `${Math.round(h * 60)} 分钟响应`
  return `${round1(h)} 小时响应`
}
function responseTone(h: number) {
  return h <= 24 ? 'fast' : 'slow'
}

/** 自动建档描述形如「农情上报处理完成：标题 - 描述」，时间线上去掉系统前缀，只留人话 */
function cleanDesc(d: string | null | undefined) {
  if (!d) return ''
  return d
    .replace(/^农情上报处理完成：/, '')
    .replace(/\s+-\s+/, ' — ')
    .trim()
}

/* ---------------- 横幅 ---------------- */

const overview = computed(() => data.value?.overview ?? null)
const response = computed(() => data.value?.response ?? null)

const heroLine = computed(() => {
  const o = overview.value
  if (!o || o.totalRecords === 0) {
    return '帮扶闭环跑通后，每一次求助与响应，都会在这里被看见。'
  }
  const r = response.value
  const parts = [`已为 ${o.helpedFarmers} 户农户对接 ${o.totalRecords} 次帮扶`]
  if (r && r.avgHours != null) parts.push(`平均 ${hourText(r.avgHours)}得到首次响应`)
  if (r && r.coverageRate != null) parts.push(`${Math.round(r.coverageRate)}% 的求助有人接住`)
  return parts.join('，') + '。'
})

const avgText = computed(() => hourText(response.value?.avgHours))
const coverageText = computed(() =>
  response.value?.coverageRate == null ? '--' : `${Math.round(response.value.coverageRate)}%`,
)
const within24Text = computed(() =>
  response.value?.within24hRate == null ? '--' : `${Math.round(response.value.within24hRate)}%`,
)

/* ---------------- 概览带 ---------------- */

const totals = computed(() => {
  const o = overview.value
  return [
    { label: '累计帮扶', value: o?.totalRecords ?? '--', sub: '帮扶记录总数' },
    { label: '惠及农户', value: o?.helpedFarmers ?? '--', sub: '接受帮扶的农户数' },
    { label: '本月新增', value: o?.monthNew ?? '--', sub: '近 30 天新建帮扶' },
    { label: '预警发布', value: o?.warningTotal ?? '--', sub: '气象预警累计条数' },
  ]
})

const closureRates = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    {
      label: '农情上报处理率',
      rate: o.reportResolveRate == null ? null : Math.round(o.reportResolveRate),
      text: `${o.reportResolved}/${o.reportTotal} 条已闭环`,
      color: '#2d6a4f',
    },
    {
      label: '农户反馈处理率',
      rate: o.feedbackHandleRate == null ? null : Math.round(o.feedbackHandleRate),
      text: `${o.feedbackHandled}/${o.feedbackTotal} 条已处理`,
      color: '#3f6fd0',
    },
    {
      label: '今日任务完成率',
      rate: o.taskTodayRate == null ? null : Math.round(o.taskTodayRate),
      text: o.taskTodayTotal === 0 ? '今日暂无任务' : `${o.taskTodayDone}/${o.taskTodayTotal} 条已完成`,
      color: '#c07d16',
    },
  ]
})

/* ---------------- 分布（DOM 条形，可访问、可被汇报截图） ---------------- */

const typeRows = computed(() => {
  const rows = [...(data.value?.byType ?? [])].sort((a, b) => b.count - a.count)
  const max = Math.max(1, ...rows.map((r) => r.count))
  return rows.map((r) => ({ ...r, pct: Math.round((r.count / max) * 100) }))
})

const villageRows = computed(() => {
  const rows = [...(data.value?.byVillage ?? [])].sort((a, b) => b.count - a.count)
  const max = Math.max(1, ...rows.map((r) => r.count))
  return rows.map((r, i) => ({
    ...r,
    rank: i + 1,
    pct: Math.round((r.count / max) * 100),
  }))
})

/* ---------------- 趋势（后端只返回有记录的日期，前端补齐 30 天零值，避免折线制造「天天有帮扶」的错觉） ---------------- */

const trendTotal = computed(() =>
  (data.value?.trend ?? []).reduce((s, t) => s + t.count, 0),
)

const trendOption = computed(() => {
  const sparse = new Map((data.value?.trend ?? []).map((t) => [t.date, t.count]))
  const labels: string[] = []
  const values: number[] = []
  const now = new Date()
  for (let i = 29; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(
      d.getDate(),
    ).padStart(2, '0')}`
    labels.push(`${d.getMonth() + 1}/${d.getDate()}`)
    values.push(sparse.get(key) ?? 0)
  }
  return {
    grid: { top: 24, right: 20, bottom: 30, left: 36 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#ffffff',
      borderColor: '#e3eae4',
      borderWidth: 1,
      textStyle: { color: '#2b3a32', fontSize: 12 },
      extraCssText: 'box-shadow:0 8px 24px rgba(27,67,50,.14);border-radius:10px;',
      axisPointer: { lineStyle: { color: '#b9c8bd' } },
      formatter: (params: any) => {
        const p = Array.isArray(params) ? params[0] : params
        return `${p.axisValue}　帮扶 <b>${p.data}</b> 条`
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dde5de' } },
      axisLabel: { color: '#8a968d', fontSize: 11, interval: 4 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#eef2ee', type: 'dashed' } },
      axisLabel: { color: '#8a968d', fontSize: 11 },
    },
    series: [
      {
        type: 'line',
        data: values,
        smooth: 0.35,
        symbol: 'circle',
        symbolSize: 6,
        showSymbol: false,
        lineStyle: { width: 2.6, color: '#2d6a4f' },
        itemStyle: { color: '#2d6a4f', borderColor: '#ffffff', borderWidth: 2 },
        emphasis: { focus: 'series', scale: 1.4 },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(45,106,79,0.24)' },
              { offset: 1, color: 'rgba(45,106,79,0.02)' },
            ],
          },
        },
      },
    ],
  }
})

/* ---------------- 作物环形 ---------------- */

const cropTotal = computed(() => (data.value?.byCrop ?? []).reduce((s, r) => s + r.count, 0))

const cropOption = computed(() => {
  const rows = data.value?.byCrop ?? []
  const palette = ['#2d6a4f', '#52b788', '#74c69d', '#c9a227', '#8a6d3b', '#3f6fd0', '#b08968', '#95d5b2']
  return {
    color: palette,
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} 条 ({d}%)',
      backgroundColor: '#ffffff',
      borderColor: '#e3eae4',
      borderWidth: 1,
      textStyle: { color: '#2b3a32', fontSize: 12 },
      extraCssText: 'box-shadow:0 8px 24px rgba(27,67,50,.14);border-radius:10px;',
    },
    title: {
      text: String(cropTotal.value),
      subtext: '条上报',
      left: 'center',
      top: '36%',
      itemGap: 2,
      textStyle: { fontSize: 26, fontWeight: 700, color: '#1d3b2c' },
      subtextStyle: { fontSize: 11, color: '#8a968d' },
    },
    legend: {
      bottom: 0,
      icon: 'circle',
      itemWidth: 8,
      itemHeight: 8,
      itemGap: 12,
      textStyle: { fontSize: 11.5, color: '#5c6b60' },
    },
    series: [
      {
        type: 'pie',
        radius: ['56%', '76%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#ffffff', borderWidth: 2, borderRadius: 5 },
        label: { show: false },
        labelLine: { show: false },
        data: rows.map((r, i) => ({
          name: r.cropName || '未填作物',
          value: r.count,
          itemStyle: { color: palette[i % palette.length] },
        })),
      },
    ],
  }
})

/* ---------------- 时间线 ---------------- */

const recent = computed<AssistanceRecentItem[]>(() => data.value?.recent ?? [])

onMounted(load)
</script>

<template>
  <div class="assist-dash">
    <!-- 成效横幅：汇报第一眼，讲清「求助有没有人接、接得有多快」 -->
    <section class="hero">
      <div class="hero-art" aria-hidden="true">
        <svg viewBox="0 0 260 190" fill="none" xmlns="http://www.w3.org/2000/svg">
          <g class="art-cloud" stroke="rgba(255,255,255,0.30)" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M168 56h18a11 11 0 0 0-2-21 15 15 0 0 0-27 5 10 10 0 0 0-11 16h22z" />
          </g>
          <g class="art-rain" stroke="rgba(255,255,255,0.34)" stroke-width="1.6" stroke-linecap="round">
            <line x1="156" y1="66" x2="152" y2="76" />
            <line x1="172" y1="66" x2="168" y2="76" />
            <line x1="188" y1="66" x2="184" y2="76" />
          </g>
          <g stroke="rgba(255,255,255,0.18)" stroke-width="1.6" stroke-linecap="round">
            <path d="M26 166 Q130 118 234 166" />
            <path d="M48 182 Q130 138 212 182" />
          </g>
          <g class="art-sprout" stroke="rgba(201,162,39,0.66)" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <line x1="130" y1="158" x2="130" y2="132" />
            <path d="M130 146 Q118 143 113 132 Q125 132 130 143" />
            <path d="M130 139 Q142 136 147 125 Q135 126 130 137" />
          </g>
        </svg>
      </div>

      <div class="hero-body">
        <div class="hero-head">
          <div>
            <h1>帮扶成效看板</h1>
            <p class="hero-sub">覆盖帮扶台账、响应时效与需求分布，可导出 Excel 报表用于汇报</p>
          </div>
          <button
            class="export-btn"
            :disabled="exporting || loading"
            @click="handleExport"
          >
            <el-icon><Download /></el-icon>
            <span>{{ exporting ? '导出中…' : '导出 Excel' }}</span>
          </button>
        </div>

        <p class="hero-line">{{ heroLine }}</p>

        <div class="hero-metrics">
          <div class="hm">
            <div class="hm-value num">{{ avgText }}</div>
            <div class="hm-label">平均首次响应</div>
            <div class="hm-sub">农情上报 → 首次帮扶指导</div>
          </div>
          <div class="hm-sep" aria-hidden="true" />
          <div class="hm">
            <div class="hm-value num">{{ coverageText }}</div>
            <div class="hm-label">响应覆盖率</div>
            <div class="hm-sub num">
              {{ response ? `${response.respondedReports}/${response.reportTotal} 条上报已响应` : '暂无上报' }}
            </div>
          </div>
          <div class="hm-sep" aria-hidden="true" />
          <div class="hm">
            <div class="hm-value num">{{ within24Text }}</div>
            <div class="hm-label">24 小时内响应</div>
            <div class="hm-sub num">
              {{ response ? `${response.within24hCount} 条在一天内被接住` : '暂无响应' }}
            </div>
          </div>
        </div>
      </div>
    </section>

    <div v-loading="loading" class="dash-body" element-loading-background="rgba(251,252,251,0.7)">
      <!-- 失败态：给出原因与重试，而不是让页面永久空白 -->
      <section v-if="errorText" class="error-panel">
        <el-icon class="error-icon"><Warning /></el-icon>
        <div class="error-text">
          <h3>看板数据加载失败</h3>
          <p>{{ errorText }}</p>
        </div>
        <button class="retry-btn" @click="load">
          <el-icon><Refresh /></el-icon>
          <span>重新加载</span>
        </button>
      </section>

      <template v-else>
        <!-- 成效概览带：总量 + 三个闭环率，压成一条而不是七张卡片 -->
        <section class="panel overview-panel">
          <div class="totals">
            <div v-for="t in totals" :key="t.label" class="total">
              <div class="total-value num">{{ t.value }}</div>
              <div class="total-label">{{ t.label }}</div>
              <div class="total-sub">{{ t.sub }}</div>
            </div>
          </div>
          <div class="closures">
            <div v-for="c in closureRates" :key="c.label" class="closure">
              <div class="closure-head">
                <span class="closure-label">{{ c.label }}</span>
                <span class="closure-rate num">{{ c.rate == null ? '--' : `${c.rate}%` }}</span>
              </div>
              <div class="bar-track">
                <div
                  class="bar-fill"
                  :style="{ width: c.rate == null ? '0%' : `${c.rate}%`, background: c.color }"
                />
              </div>
              <div class="closure-sub">{{ c.text }}</div>
            </div>
          </div>
        </section>

        <!-- 趋势 + 类型 -->
        <div class="grid grid-2-1">
          <section class="panel">
            <header class="panel-head">
              <h2>近 30 天帮扶动态</h2>
              <span class="panel-note num">合计 {{ trendTotal }} 条</span>
            </header>
            <v-chart
              v-if="trendTotal > 0"
              class="chart chart-trend"
              :option="trendOption"
              autoresize
              role="img"
              :aria-label="`近30天帮扶记录趋势图，合计${trendTotal}条`"
            />
            <div v-else class="empty">
              <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
                <path d="M14 40 Q32 26 50 40" stroke="#cdd8cf" stroke-width="2.4" stroke-linecap="round" stroke-dasharray="3 5" />
                <circle cx="32" cy="20" r="5" stroke="#cdd8cf" stroke-width="2.4" />
              </svg>
              <p>近 30 天还没有帮扶记录</p>
            </div>
          </section>

          <section class="panel">
            <header class="panel-head">
              <h2>帮扶类型</h2>
              <span class="panel-note">按记录数</span>
            </header>
            <div v-if="typeRows.length" class="rank-list">
              <div v-for="r in typeRows" :key="r.type" class="rank-row">
                <el-icon class="rank-icon" :style="{ color: typeMeta(r.type).color }">
                  <component :is="typeMeta(r.type).icon" />
                </el-icon>
                <span class="rank-name">{{ r.type }}</span>
                <span class="rank-track">
                  <span
                    class="rank-fill"
                    :style="{ width: `${r.pct}%`, background: typeMeta(r.type).color }"
                  />
                </span>
                <span class="rank-count num">{{ r.count }}</span>
              </div>
            </div>
            <div v-else class="empty compact">
              <p>暂无帮扶记录</p>
            </div>
          </section>
        </div>

        <!-- 村镇 + 作物 -->
        <div class="grid grid-3-2">
          <section class="panel">
            <header class="panel-head">
              <h2>分村镇帮扶分布</h2>
              <span class="panel-note">按农户所属村镇</span>
            </header>
            <div v-if="villageRows.length" class="rank-list">
              <div v-for="r in villageRows" :key="r.villageCode ?? r.villageName" class="rank-row">
                <span class="rank-no num" :class="{ top: r.rank <= 3, r1: r.rank === 1 }">{{ r.rank }}</span>
                <span class="rank-name">{{ r.villageName }}</span>
                <span class="rank-track">
                  <span
                    class="rank-fill"
                    :class="{ gold: r.rank === 1 }"
                    :style="{ width: `${r.pct}%` }"
                  />
                </span>
                <span class="rank-count num">{{ r.count }}</span>
              </div>
            </div>
            <div v-else class="empty compact"><p>暂无村镇分布数据</p></div>
          </section>

          <section class="panel">
            <header class="panel-head">
              <h2>帮扶需求作物</h2>
              <span class="panel-note">按农情上报</span>
            </header>
            <v-chart
              v-if="cropTotal > 0"
              class="chart chart-crop"
              :option="cropOption"
              autoresize
              role="img"
              :aria-label="`帮扶需求作物分布环形图，合计${cropTotal}条上报`"
            />
            <div v-else class="empty compact">
              <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
                <circle cx="32" cy="32" r="20" stroke="#cdd8cf" stroke-width="2.4" stroke-dasharray="4 6" />
              </svg>
              <p>暂无作物分布数据</p>
            </div>
          </section>
        </div>

        <!-- 最近帮扶动态：让看板读到「谁的困难、多久被接住」 -->
        <section class="panel timeline-panel">
          <header class="panel-head">
            <h2>最近帮扶动态</h2>
            <span class="panel-note">最近 {{ recent.length }} 条闭环帮扶</span>
          </header>

          <ol v-if="recent.length" class="timeline">
            <li v-for="item in recent" :key="item.id" class="tl-item">
              <span
                class="tl-dot"
                :style="{
                  color: typeMeta(item.type).color,
                  background: `${typeMeta(item.type).color}1a`,
                }"
              >
                <el-icon><component :is="typeMeta(item.type).icon" /></el-icon>
              </span>
              <span class="tl-rail" aria-hidden="true" />

              <div class="tl-content">
                <div class="tl-title">
                  <span class="tl-type" :style="{ color: typeMeta(item.type).color }">{{ item.type }}</span>
                  <span class="tl-status" :class="statusClass(item.status)">{{ item.status || '未知' }}</span>
                  <span class="tl-farmer">{{ item.farmerName }} · {{ item.villageName }}</span>
                </div>
                <p v-if="cleanDesc(item.description)" class="tl-desc">{{ cleanDesc(item.description) }}</p>
              </div>

              <div class="tl-meta">
                <span
                  v-if="item.responseHours != null"
                  class="tl-hours"
                  :class="responseTone(item.responseHours)"
                >{{ responseHoursText(item.responseHours) }}</span>
                <span class="tl-time num">{{ item.createdAt || '' }}</span>
              </div>
            </li>
          </ol>

          <div v-else class="empty">
            <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
              <path d="M32 46 V30" stroke="#cdd8cf" stroke-width="2.4" stroke-linecap="round" />
              <path d="M32 38 Q22 36 19 27 Q28 27 32 35" stroke="#cdd8cf" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M32 33 Q42 31 45 22 Q36 22 32 30" stroke="#cdd8cf" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <p>还没有帮扶记录，第一条闭环帮扶会出现在这里</p>
          </div>
        </section>
      </template>
    </div>
  </div>
</template>

<style scoped>
.assist-dash {
  --ink: #1d3b2c;
  --ink-soft: #46554d;
  --ink-faint: #8a968d;
  --line: rgba(45, 106, 79, 0.1);
  --green: #2d6a4f;
  --gold: #c9a227;
}

/* ---------------- 成效横幅 ---------------- */

.hero {
  position: relative;
  overflow: hidden;
  border-radius: 20px;
  padding: 26px 30px 24px;
  color: #ffffff;
  background: linear-gradient(132deg, #2f7a59 0%, #246044 46%, #173f2d 100%);
  box-shadow: 0 10px 30px rgba(23, 63, 45, 0.22);
}

.hero-art {
  position: absolute;
  right: 18px;
  top: -6px;
  width: 250px;
  opacity: 0.9;
  pointer-events: none;
}
.hero-art svg {
  width: 100%;
  display: block;
}
.art-rain line {
  animation: rain-fall 2.6s linear infinite;
  transform-box: fill-box;
  transform-origin: center;
}
.art-rain line:nth-child(2) { animation-delay: 0.5s; }
.art-rain line:nth-child(3) { animation-delay: 1s; }
.art-cloud { animation: cloud-drift 9s ease-in-out infinite alternate; transform-box: fill-box; }
@keyframes rain-fall {
  0% { transform: translateY(-4px); opacity: 0; }
  25% { opacity: 1; }
  75% { opacity: 1; }
  100% { transform: translateY(6px); opacity: 0; }
}
@keyframes cloud-drift {
  from { transform: translateX(-3px); }
  to { transform: translateX(4px); }
}
@media (prefers-reduced-motion: reduce) {
  .art-rain line, .art-cloud { animation: none; }
}

.hero-body {
  position: relative;
  z-index: 1;
}

.hero-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.hero h1 {
  margin: 0;
  font-size: 25px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.hero-sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.72);
}

.export-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 10px 20px;
  border: none;
  border-radius: 999px;
  background: var(--gold);
  color: #20392b;
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
  box-shadow: 0 4px 14px rgba(201, 162, 39, 0.32);
}
.export-btn:hover:not(:disabled) {
  background: #d8b43e;
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(201, 162, 39, 0.42);
}
.export-btn:active:not(:disabled) { transform: translateY(0); }
.export-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.export-btn:focus-visible { outline: 2px solid #f0d878; outline-offset: 2px; }

.hero-line {
  margin: 16px 0 0;
  font-size: 14.5px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.9);
  max-width: 640px;
}

.hero-metrics {
  margin-top: 20px;
  display: flex;
  align-items: stretch;
  gap: 26px;
}
.hm {
  min-width: 150px;
}
.hm-value {
  font-size: 30px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: 0.3px;
}
.hm-label {
  margin-top: 3px;
  font-size: 13px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
}
.hm-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.58);
}
.hm-sep {
  width: 1px;
  align-self: center;
  height: 42px;
  background: rgba(255, 255, 255, 0.16);
}

/* ---------------- 主体面板 ---------------- */

.dash-body {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 320px;
}

.panel {
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 18px 22px 20px;
  box-shadow: 0 4px 22px rgba(27, 67, 50, 0.06);
  backdrop-filter: blur(8px);
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.panel-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
}
.panel-note {
  font-size: 12px;
  color: var(--ink-faint);
}

.grid {
  display: grid;
  gap: 16px;
}
.grid-2-1 { grid-template-columns: 2fr 1fr; }
.grid-3-2 { grid-template-columns: 1.35fr 1fr; }

.chart { width: 100%; }
.chart-trend { height: 280px; }
.chart-crop { height: 268px; }

/* ---------------- 概览带 ---------------- */

.overview-panel {
  display: grid;
  grid-template-columns: 1.25fr 2fr;
  gap: 24px;
  align-items: center;
  padding: 20px 26px;
}

.totals {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.total-value {
  font-size: 25px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.2;
}
.total-label {
  margin-top: 3px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-soft);
}
.total-sub {
  margin-top: 2px;
  font-size: 11px;
  color: var(--ink-faint);
}

.closures {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 22px;
  padding-left: 26px;
  border-left: 1px solid var(--line);
}
.closure-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.closure-label { font-size: 12.5px; color: var(--ink-soft); }
.closure-rate { font-size: 15px; font-weight: 700; color: var(--ink); }
.bar-track {
  margin-top: 7px;
  height: 8px;
  border-radius: 99px;
  background: #edf1ec;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 99px;
  transform-origin: left center;
  animation: grow-x 0.9s cubic-bezier(0.22, 0.8, 0.3, 1) both;
}
@keyframes grow-x {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}
.closure-sub { margin-top: 6px; font-size: 11px; color: var(--ink-faint); }

/* ---------------- 排名条形 ---------------- */

.rank-list { display: flex; flex-direction: column; gap: 13px; }
.rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.rank-icon {
  font-size: 16px;
  flex-shrink: 0;
}
.rank-no {
  width: 18px;
  text-align: center;
  font-size: 12px;
  font-weight: 600;
  color: #a7b3aa;
  flex-shrink: 0;
}
.rank-no.top { color: var(--green); }
.rank-no.r1 { color: var(--gold); }
.rank-name {
  width: 84px;
  flex-shrink: 0;
  color: var(--ink-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.rank-track {
  flex: 1;
  height: 8px;
  border-radius: 99px;
  background: #edf1ec;
  overflow: hidden;
}
.rank-fill {
  display: block;
  height: 100%;
  border-radius: 99px;
  background: var(--green);
  opacity: 0.88;
  transform-origin: left center;
  animation: grow-x 0.9s cubic-bezier(0.22, 0.8, 0.3, 1) both;
}
.rank-fill.gold {
  background: linear-gradient(90deg, #d8b43e, var(--gold));
  opacity: 1;
}
.rank-count {
  width: 30px;
  text-align: right;
  font-weight: 600;
  color: var(--ink);
  flex-shrink: 0;
}

/* ---------------- 空态 / 错误 ---------------- */

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px 20px;
  color: var(--ink-faint);
  text-align: center;
}
.empty.compact { padding: 26px 16px; }
.empty svg { width: 52px; height: 52px; }
.empty p { margin: 0; font-size: 13px; }

.error-panel {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px 24px;
  border-radius: 16px;
  background: rgba(176, 69, 43, 0.06);
  border: 1px solid rgba(176, 69, 43, 0.22);
}
.error-icon { font-size: 24px; color: #b0452b; flex-shrink: 0; }
.error-text { flex: 1; }
.error-text h3 { margin: 0; font-size: 14.5px; color: #8a3520; }
.error-text p { margin: 4px 0 0; font-size: 12.5px; color: #a05a48; }
.retry-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 999px;
  border: 1px solid rgba(176, 69, 43, 0.4);
  background: #ffffff;
  color: #8a3520;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.18s ease;
}
.retry-btn:hover { background: rgba(176, 69, 43, 0.08); }
.retry-btn:focus-visible { outline: 2px solid #b0452b; outline-offset: 2px; }

/* ---------------- 时间线 ---------------- */

.timeline {
  list-style: none;
  margin: 0;
  padding: 0;
}
.tl-item {
  position: relative;
  display: grid;
  grid-template-columns: 30px 1fr auto;
  gap: 12px;
  padding-bottom: 18px;
}
.tl-item:last-child { padding-bottom: 0; }

.tl-dot {
  position: relative;
  z-index: 1;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  margin-top: 1px;
}
.tl-dot .el-icon { font-size: 14px; }
.tl-rail {
  position: absolute;
  left: 14px;
  top: 31px;
  bottom: -1px;
  width: 2px;
  background: #e7ede8;
}
.tl-item:last-child .tl-rail { display: none; }

.tl-content { min-width: 0; }
.tl-title {
  display: flex;
  align-items: center;
  gap: 9px;
  flex-wrap: wrap;
}
.tl-type { font-size: 13.5px; font-weight: 600; }
.tl-status {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 99px;
  line-height: 1.6;
}
.st-done { background: rgba(45, 106, 79, 0.1); color: #2d6a4f; }
.st-doing { background: rgba(63, 111, 208, 0.1); color: #3f6fd0; }
.st-pending { background: rgba(192, 125, 22, 0.12); color: #b07d17; }
.tl-farmer { font-size: 12px; color: var(--ink-faint); }
.tl-desc {
  margin: 5px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: #5c6b60;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tl-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 5px;
  padding-top: 2px;
  white-space: nowrap;
}
.tl-hours {
  font-size: 11.5px;
  font-weight: 600;
  padding: 2px 9px;
  border-radius: 99px;
}
.tl-hours.fast { background: rgba(45, 106, 79, 0.1); color: #2d6a4f; }
.tl-hours.slow { background: rgba(192, 125, 22, 0.12); color: #b07d17; }
.tl-time { font-size: 11.5px; color: #a7b3aa; }

/* ---------------- 浏览器表面 ---------------- */

.assist-dash ::selection {
  background: rgba(45, 106, 79, 0.18);
}
.num {
  font-variant-numeric: tabular-nums;
  font-feature-settings: 'tnum';
}

/* ---------------- 响应式 ---------------- */

@media (max-width: 1280px) {
  .grid-2-1, .grid-3-2 { grid-template-columns: 1fr; }
}
@media (max-width: 1080px) {
  .overview-panel { grid-template-columns: 1fr; gap: 18px; }
  .closures { padding-left: 0; border-left: none; border-top: 1px solid var(--line); padding-top: 18px; }
}
@media (max-width: 760px) {
  .hero { padding: 22px 20px; }
  .hero-art { width: 170px; opacity: 0.6; }
  .hero-head { flex-direction: column-reverse; }
  .hero-metrics { flex-wrap: wrap; gap: 16px; }
  .hm-sep { display: none; }
  .hm { min-width: calc(50% - 8px); }
  .totals { grid-template-columns: repeat(2, 1fr); gap: 16px; }
  .closures { grid-template-columns: 1fr; gap: 14px; }
  .tl-item { grid-template-columns: 30px 1fr; }
  .tl-meta { grid-column: 2; flex-direction: row; align-items: center; }
  .chart-trend { height: 240px; }
}
</style>

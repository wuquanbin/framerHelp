<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { Component } from 'vue'
import { getVillages } from '@/api/weather'
import {
  adminSendAiChat,
  createFarmAdvice,
  listAiGenerations,
  saveAiGeneration,
  deleteAiGeneration,
  markAiGenerationPublished,
} from '@/api/admin'
import type { AiGenerationRecord } from '@/api/admin'
import type { VillageOption } from '@/types/weather'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  Loading,
  Document,
  CopyDocument,
  Monitor,
  Check,
  Promotion,
  MagicStick,
  Notebook,
  Connection,
  Warning,
  DataAnalysis,
  Cpu,
  EditPen,
  Location,
  Clock,
  Delete,
  User,
  Upload,
  Download,
} from '@element-plus/icons-vue'
import { markdownToHtml } from '@/utils/markdown'

type ToolTone = 'green' | 'amber' | 'teal' | 'slate'

const villages = ref<VillageOption[]>([])
const currentVillage = ref('')
const loading = ref(false)
const activeTab = ref('generate')

// 生成的结果列表
interface GeneratedResult {
  id: number
  type: string
  title: string
  village: string
  content: string
  time: string // ISO 时间字符串，便于本地持久化后重新解析
  tone: ToolTone
  // 农事建议是否已发布过（仅本机标记，用于按钮提示）
  published?: boolean
}

// ============ 生成记录持久化（落库，不再存浏览器本地） ============
// 以前写 localStorage：换台电脑、换个浏览器、换个账号就全没了，也没法审计。
// 现在走 /admin/ai-generations，全端共享、可追溯。

/** 把后端记录还原成列表需要的结构（村镇名要查一次映射，失败就用编码兜底） */
function toResult(r: AiGenerationRecord): GeneratedResult {
  const villageName =
    villages.value.find((v) => v.code === r.villageCode)?.name || r.villageCode || '未指定村镇'
  return {
    id: r.id,
    type: r.toolTitle,
    title: `${r.toolTitle} - ${villageName}`,
    village: villageName,
    content: r.content,
    time: r.createdAt,
    tone: toneFor(r.toolTitle),
    published: r.published,
  }
}

/** 工具配色从工具定义里查，历史记录里同名工具自然后一致；查不到用中性色 */
function toneFor(toolTitle: string): ToolTone {
  return managementTools.find((t) => t.title === toolTitle)?.tone ?? 'slate'
}

async function loadResults() {
  try {
    const list = await listAiGenerations()
    results.value = list.map(toResult)
  } catch {
    ElMessage.error('加载生成记录失败')
  }
}

/** 生成完成后落库；失败也要把结果留在界面上，但不能假装已保存 */
async function persistNewResult(payload: {
  toolTitle: string
  villageCode: string
  prompt: string
  content: string
}): Promise<GeneratedResult | null> {
  try {
    return toResult(await saveAiGeneration(payload))
  } catch {
    ElMessage.warning('生成成功，但保存到服务器失败，刷新后这条会丢失')
    return null
  }
}

// 图标不可序列化，按记录类型在渲染时还原
function iconFor(r: { type: string; tone: ToolTone }): Component {
  const byType: Record<string, Component> = {
    生成农事建议: Notebook,
    撰写农情报告: Document,
    制定帮扶方案: Connection,
    灾害影响评估: Warning,
    数据异常排查: DataAnalysis,
    系统运行报告: Cpu,
    自定义指令: EditPen,
  }
  const hit = byType[r.type]
  if (hit) return hit
  const byTone: Record<ToolTone, Component> = {
    green: Notebook,
    amber: Warning,
    teal: DataAnalysis,
    slate: EditPen,
  }
  return byTone[r.tone] ?? Document
}

// 记录不再从本地读，进页面时由 loadResults() 拉一次
const results = ref<GeneratedResult[]>([])
// 当前在右侧预览中查看的记录（null 时默认展示最新一条）
const selectedId = ref<number | null>(null)
const selectedResult = computed(
  () => results.value.find((r) => r.id === selectedId.value) ?? results.value[0] ?? null,
)

// 管理工具定义 - 面向管理员的操作，不是面向用户的提问
const managementTools: Array<{
  icon: Component
  title: string
  desc: string
  category: string
  tone: ToolTone
  prompt: string
}> = [
  {
    icon: Notebook,
    title: '生成农事建议',
    desc: '为选定村庄批量生成农事指导内容，可发布至小程序',
    category: '内容管理',
    tone: 'green',
    prompt:
      '你是一个农业管理助手。请根据当前天气数据，为管理员生成一份规范的农事建议文档，使用Markdown格式输出，合理使用标题、列表、加粗标记。格式包含：1.本周天气概要 2.田间管理要点 3.施肥灌溉建议 4.病虫害预警 5.采收建议。内容需专业、简洁，适合直接发布给农户。',
  },
  {
    icon: Document,
    title: '撰写农情报告',
    desc: '生成阶段性农情分析报告，支持导出',
    category: '内容管理',
    tone: 'green',
    prompt:
      '你是一个农业管理助手。请为管理员撰写一份阶段性农情分析报告，使用Markdown格式输出，合理使用标题、列表、加粗标记。包含：1.总体农情概述 2.各作物生长状况 3.气象影响分析 4.病虫害发生情况 5.下阶段工作建议。格式规范，适合归档和上报。',
  },
  {
    icon: Connection,
    title: '制定帮扶方案',
    desc: '为帮扶对象生成个性化帮扶方案',
    category: '内容管理',
    tone: 'green',
    prompt:
      '你是一个农业管理助手。请为管理员生成一份农业帮扶方案模板，使用Markdown格式输出，合理使用标题、列表、加粗标记。包含：1.帮扶对象基本情况 2.技术指导计划 3.物资需求清单 4.市场对接建议 5.帮扶进度跟踪。内容需具体可执行。',
  },
  {
    icon: Warning,
    title: '灾害影响评估',
    desc: '评估灾害天气对辖区农业的影响及应对',
    category: '应急指挥',
    tone: 'amber',
    prompt:
      '你是一个农业应急管理助手。请为管理员生成一份灾害影响评估报告，使用Markdown格式输出，合理使用标题、列表、加粗标记。包含：1.灾害天气描述 2.受影响区域和作物 3.预计损失评估 4.应急响应措施 5.灾后恢复计划。语气正式，适合决策参考。',
  },
  {
    icon: DataAnalysis,
    title: '数据异常排查',
    desc: '分析近期数据是否存在异常或缺失',
    category: '运维监控',
    tone: 'teal',
    prompt:
      '你是一个系统运维助手。请为管理员分析当前农业气象数据可能存在的异常情况，使用Markdown格式输出，合理使用标题、列表、加粗标记。包含：1.数据完整性检查 2.气象数据合理性分析 3.预警信息覆盖率 4.数据更新及时性 5.问题处置建议。',
  },
  {
    icon: Cpu,
    title: '系统运行报告',
    desc: '生成系统运行状态报告',
    category: '运维监控',
    tone: 'teal',
    prompt:
      '你是一个系统运维助手。请为管理员生成一份系统运行状态报告，使用Markdown格式输出，合理使用标题、列表、加粗标记。包含：1.数据同步状态 2.天气API调用情况 3.预警推送统计 4.用户访问概况 5.优化建议。格式规范，适合运维归档。',
  },
]

// 按类别分组
const categories = ref<string[]>([])
const toolsByCategory = ref<Record<string, typeof managementTools>>({})

function groupTools() {
  const map: Record<string, typeof managementTools> = {}
  const cats: string[] = []
  for (const t of managementTools) {
    let list = map[t.category]
    if (!list) {
      list = []
      map[t.category] = list
      cats.push(t.category)
    }
    list.push(t)
  }
  categories.value = cats
  toolsByCategory.value = map
}
groupTools()

// 自由指令输入
const commandInput = ref('')

// 执行管理工具
async function executeTool(tool: (typeof managementTools)[0]) {
  if (!currentVillage.value) {
    ElMessage.warning('请先选择目标村庄')
    return
  }
  loading.value = true
  const villageName = villages.value.find((v) => v.code === currentVillage.value)?.name || currentVillage.value

  try {
    const res = await adminSendAiChat({
      message: tool.prompt,
      villageCode: currentVillage.value,
    })
    const saved = await persistNewResult({
      toolTitle: tool.title,
      villageCode: currentVillage.value,
      prompt: tool.prompt,
      content: res.reply || '生成失败',
    })
    if (saved) {
      results.value.unshift(saved)
      selectedId.value = saved.id
    } else {
      // 保存失败时仍然展示内容，用负数 id 标记「未落库」，避免与后端 id 撞号
      const tempId = -Date.now()
      results.value.unshift({
        id: tempId,
        type: tool.title,
        title: `${tool.title} - ${villageName}`,
        village: villageName,
        content: res.reply || '生成失败',
        time: new Date().toISOString(),
        tone: tool.tone,
      })
      selectedId.value = tempId
    }
    activeTab.value = 'results'
    ElMessage.success(`${tool.title}生成完成`)
  } catch {
    ElMessage.error('AI服务调用失败，请检查配置')
  } finally {
    loading.value = false
  }
}

// 执行自由指令
async function executeCommand() {
  const cmd = commandInput.value.trim()
  if (!cmd || loading.value) return
  if (!currentVillage.value) {
    ElMessage.warning('请先选择目标村庄')
    return
  }
  loading.value = true
  const villageName = villages.value.find((v) => v.code === currentVillage.value)?.name || currentVillage.value
  const adminPrompt = `你是一个农业管理助手，面向管理员提供专业管理支持。请使用Markdown格式输出，合理使用标题、列表、加粗标记。针对以下管理需求给出专业、结构化的回答：\n${cmd}`

  try {
    const res = await adminSendAiChat({
      message: adminPrompt,
      villageCode: currentVillage.value,
    })
    const saved = await persistNewResult({
      toolTitle: '自定义指令',
      villageCode: currentVillage.value,
      prompt: cmd,
      content: res.reply || '生成失败',
    })
    const item: GeneratedResult = saved ?? {
      id: -Date.now(),
      type: '自定义指令',
      title: `自定义指令 - ${villageName}`,
      village: villageName,
      content: res.reply || '生成失败',
      time: new Date().toISOString(),
      tone: 'slate',
    }
    results.value.unshift(item)
    selectedId.value = item.id
    activeTab.value = 'results'
    commandInput.value = ''
  } catch {
    ElMessage.error('AI服务调用失败')
  } finally {
    loading.value = false
  }
}

// 复制结果到剪贴板
async function copyResult(content: string) {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败')
  }
}

// ============ 农事建议一键发布 ============

// 图标 key 与农事建议管理页保持一致
const adviceIconOptions = [
  { key: 'Sunny', label: '种植' },
  { key: 'Watermelon', label: '灌溉' },
  { key: 'Warning', label: '虫害' },
  { key: 'Box', label: '收获' },
  { key: 'Sunrise', label: '晴天' },
  { key: 'Cloudy', label: '多云' },
  { key: 'Drizzling', label: '雨天' },
  { key: 'WindPower', label: '大风' },
  { key: 'Crop', label: '小麦' },
  { key: 'Orange', label: '玉米' },
  { key: 'Cherry', label: '蔬菜' },
  { key: 'Grape', label: '水果' },
  { key: 'Apple', label: '花卉' },
  { key: 'Bell', label: '生长' },
  { key: 'InfoFilled', label: '警告' },
  { key: 'QuestionFilled', label: '提醒' },
  { key: 'Setting', label: '设置' },
  { key: 'Tools', label: '工具' },
]

const publishVisible = ref(false)
const publishing = ref(false)
const publishTarget = ref<GeneratedResult | null>(null)
const publishForm = ref({
  title: '',
  summary: '',
  village: '',
  priorityTag: '中',
  iconKey: 'Sunny',
  priority: 5,
})

function openPublish(r: GeneratedResult) {
  publishTarget.value = r
  const title = reportTitle(r.content, r.title).slice(0, 64)
  // 摘要取去掉标题后的正文开头，控制在 200 字内（数据库列上限 255）
  const excerpt = reportExcerpt(r.content, title).replace(/…$/, '').slice(0, 200)
  const matched = villages.value.find((v) => v.name === r.village)
  publishForm.value = {
    title,
    summary: excerpt,
    village: matched?.code || currentVillage.value,
    priorityTag: '中',
    iconKey: 'Sunny',
    priority: 5,
  }
  publishVisible.value = true
}

async function confirmPublish() {
  if (!publishTarget.value) return
  if (!publishForm.value.village) {
    ElMessage.warning('请选择所属村镇')
    return
  }
  if (!publishForm.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!publishForm.value.summary.trim()) {
    ElMessage.warning('请填写摘要')
    return
  }
  publishing.value = true
  try {
    const advice = (await createFarmAdvice({
      title: publishForm.value.title.trim(),
      summary: publishForm.value.summary.trim(),
      recommendation: publishTarget.value.content,
      priorityTag: publishForm.value.priorityTag,
      iconKey: publishForm.value.iconKey,
      priority: publishForm.value.priority,
      village: publishForm.value.village,
    })) as { id?: number } | undefined
    publishTarget.value.published = true
    // 回写「已发布」标记：本地记录（负数 id）没有落库，跳过即可
    if (publishTarget.value.id > 0) {
      try {
        await markAiGenerationPublished(publishTarget.value.id, advice?.id)
      } catch {
        /* 标记失败不影响已经发布成功的农事建议 */
      }
    }
    ElMessage.success('已发布到农事建议，可在「生产管理 → 农事建议」中查看编辑')
    publishVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

// ============ 导出为 Markdown 文件（报告/方案/评估类结果的归档路径） ============

function exportMarkdown(r: GeneratedResult) {
  const title = reportTitle(r.content, r.title)
  const d = new Date(r.time)
  const p = (n: number) => String(n).padStart(2, '0')
  const date = Number.isNaN(d.getTime()) ? '' : `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}`
  const fileName = `${title}-${r.village}${date ? '-' + date : ''}.md`.replace(/[\\/:*?"<>|]/g, '_')
  const blob = new Blob([r.content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// 在左侧列表中选中一条记录
function selectResult(id: number) {
  selectedId.value = id
}

// 删除结果（同时删服务端记录；未落库的本地记录直接丢掉即可）
async function removeResult(id: number) {
  results.value = results.value.filter((r) => r.id !== id)
  if (selectedId.value === id) selectedId.value = null
  if (id > 0) {
    try {
      await deleteAiGeneration(id)
    } catch {
      ElMessage.error('删除失败，请刷新后重试')
    }
  }
}

// 清空所有结果（二次确认，避免误删整段工作记录）
async function clearResults() {
  try {
    await ElMessageBox.confirm(`确定清空全部 ${results.value.length} 条生成记录吗？此操作不可恢复。`, '清空记录', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  const ids = results.value.map((r) => r.id).filter((id) => id > 0)
  results.value = []
  selectedId.value = null
  if (ids.length) {
    try {
      await Promise.all(ids.map((id) => deleteAiGeneration(id)))
    } catch {
      ElMessage.error('部分记录删除失败，请刷新后重试')
    }
  }
}

// ============ Markdown 文本提取（供列表卡片展示标题与摘要） ============
function stripMarkdown(md: string): string {
  return md
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}#{1,6}\s*/gm, '')
    .replace(/[*_~]{1,3}([^*_~]+)[*_~]{1,3}/g, '$1')
    .replace(/^\s*>\s?/gm, '')
    .replace(/^\s*[-+*]\s+/gm, '')
    .replace(/^\s*\d+[.)]\s+/gm, '')
    .replace(/\|/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function reportTitle(content: string, fallback: string): string {
  const heading = /^\s{0,3}#{1,3}\s+(.+?)\s*#*\s*$/m.exec(content)
  if (heading?.[1]) {
    const t = stripMarkdown(heading[1])
    if (t) return t
  }
  const bold = /^\s*\*\*([^*\n]{2,40})\*\*\s*$/m.exec(content)
  if (bold?.[1]) return bold[1].trim()
  return fallback
}

function reportExcerpt(content: string, title: string): string {
  let text = stripMarkdown(content)
  if (title && text.startsWith(title)) text = text.slice(title.length)
  text = text.replace(/^[\s:：，,。.、-]+/, '').trim()
  if (!text) return '点击卡片查看完整内容'
  return text.length > 84 ? `${text.slice(0, 84)}…` : text
}

// 列表卡片用的短时间：当天显示「今天 HH:mm」，否则显示「MM-DD HH:mm」
function shortTime(time: string): string {
  const d = new Date(time)
  if (Number.isNaN(d.getTime())) return time
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const now = new Date()
  if (d.toDateString() === now.toDateString()) return `今天 ${hm}`
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hm}`
}

// 预览头部用的完整时间：yyyy-MM-dd HH:mm
function fullTime(time: string): string {
  const d = new Date(time)
  if (Number.isNaN(d.getTime())) return time
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// Markdown 渲染
function renderMarkdown(text: string): string {
  return markdownToHtml(text)
}

onMounted(async () => {
  try {
    const d = await getVillages()
    villages.value = d
    if (d[0]) currentVillage.value = d[0].code
  } catch {}
  // 村镇列表要先就位，否则历史记录里的村镇名只能退回显示编码
  await loadResults()
})
</script>

<template>
  <div class="ai-console-page">
    <!-- 顶部控制栏 -->
    <div class="console-header">
      <div class="header-left">
        <span class="header-mark"><el-icon><MagicStick /></el-icon></span>
        <span class="header-title">AI管理指挥台</span>
        <span class="header-chip"><el-icon><User /></el-icon>管理员工具</span>
      </div>
      <div class="header-right">
        <span class="village-label"><el-icon><Location /></el-icon>目标村庄</span>
        <el-select v-model="currentVillage" class="village-select" placeholder="选择村庄">
          <el-option v-for="v in villages" :key="v.code" :label="v.name" :value="v.code" />
        </el-select>
      </div>
    </div>

    <div class="console-body">
      <!-- 左侧：管理工具面板 -->
      <div class="tool-panel">
        <el-tabs v-model="activeTab" class="tool-tabs">
          <el-tab-pane label="管理工具" name="generate">
            <div v-for="cat in categories" :key="cat" class="tool-category">
              <div class="category-label">
                <span class="category-dot" />{{ cat }}
              </div>
              <div class="tool-grid">
                <div
                  v-for="tool in toolsByCategory[cat]"
                  :key="tool.title"
                  class="tool-card"
                  :class="['tone-' + tool.tone, { 'tool-disabled': loading }]"
                  role="button"
                  tabindex="0"
                  @click="executeTool(tool)"
                  @keydown.enter.prevent="executeTool(tool)"
                  @keydown.space.prevent="executeTool(tool)"
                >
                  <span class="tool-icon-tile"><el-icon><component :is="tool.icon" /></el-icon></span>
                  <div class="tool-info">
                    <div class="tool-title">{{ tool.title }}</div>
                    <div class="tool-desc">{{ tool.desc }}</div>
                  </div>
                  <el-icon v-if="!loading" class="tool-action"><ArrowRight /></el-icon>
                  <el-icon v-else class="tool-action is-loading"><Loading /></el-icon>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="results">
            <template #label>
              <span class="tab-label">生成记录<span v-if="results.length" class="tab-count">{{ results.length }}</span></span>
            </template>
            <div v-if="results.length === 0" class="results-empty">
              <span class="results-empty-icon"><el-icon :size="26"><Document /></el-icon></span>
              <p class="results-empty-title">暂无生成记录</p>
              <p class="results-empty-sub">使用「管理工具」生成内容后，记录会显示在这里并自动保存在本机，刷新不丢失</p>
            </div>
            <div v-else class="results-list">
              <div class="results-toolbar">
                <span class="results-count">共 <strong>{{ results.length }}</strong> 条记录</span>
                <button class="results-clear" type="button" @click="clearResults">
                  <el-icon><Delete /></el-icon>清空全部
                </button>
              </div>
              <div
                v-for="r in results"
                :key="r.id"
                class="result-item"
                :class="['tone-' + r.tone, { active: selectedResult?.id === r.id }]"
                role="button"
                tabindex="0"
                :aria-label="`查看记录：${reportTitle(r.content, r.title)}`"
                @click="selectResult(r.id)"
                @keydown.enter.prevent="selectResult(r.id)"
                @keydown.space.prevent="selectResult(r.id)"
              >
                <div class="result-top">
                  <span class="result-chip"><el-icon><component :is="iconFor(r)" /></el-icon>{{ r.type }}</span>
                  <span class="result-time"><el-icon><Clock /></el-icon>{{ shortTime(r.time) }}</span>
                </div>
                <div class="result-title">{{ reportTitle(r.content, r.title) }}</div>
                <div class="result-excerpt">{{ reportExcerpt(r.content, reportTitle(r.content, r.title)) }}</div>
                <div class="result-foot">
                  <span class="result-village"><el-icon><Location /></el-icon>{{ r.village }}</span>
                  <span class="result-actions">
                    <button
                      v-if="r.type === '生成农事建议'"
                      class="icon-btn publish"
                      type="button"
                      :title="r.published ? '已发布，点击再次发布' : '发布到农事建议'"
                      :aria-label="r.published ? '已发布，点击再次发布' : '发布到农事建议'"
                      @click.stop="openPublish(r)"
                    >
                      <el-icon><Upload /></el-icon>
                    </button>
                    <button
                      v-else
                      class="icon-btn"
                      type="button"
                      title="导出为 Markdown 文档"
                      aria-label="导出为 Markdown 文档"
                      @click.stop="exportMarkdown(r)"
                    >
                      <el-icon><Download /></el-icon>
                    </button>
                    <button
                      class="icon-btn"
                      type="button"
                      title="复制内容"
                      aria-label="复制内容"
                      @click.stop="copyResult(r.content)"
                    >
                      <el-icon><CopyDocument /></el-icon>
                    </button>
                    <button
                      class="icon-btn danger"
                      type="button"
                      title="删除记录"
                      aria-label="删除记录"
                      @click.stop="removeResult(r.id)"
                    >
                      <el-icon><Delete /></el-icon>
                    </button>
                  </span>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>

      <!-- 右侧：结果预览区 -->
      <div class="preview-panel">
        <!-- 等待 AI 返回时用转圈圈加载态代替，避免界面无反馈 -->
        <div v-if="loading" class="preview-loading">
          <el-icon class="preview-loading-icon" :size="40"><Loading /></el-icon>
          <p class="preview-loading-title">AI 正在生成中，请稍候…</p>
          <p class="preview-loading-desc">内容较长时可能需要等待数十秒，期间请勿重复点击</p>
          <div class="skeleton-card" aria-hidden="true">
            <span class="sk sk-title" />
            <span class="sk sk-line w90" />
            <span class="sk sk-line" />
            <span class="sk sk-line w70" />
          </div>
        </div>
        <div v-else-if="results.length === 0" class="preview-empty">
          <div class="preview-empty-icon">
            <el-icon :size="38"><Monitor /></el-icon>
          </div>
          <p class="preview-empty-title">管理指挥台</p>
          <p class="preview-empty-desc">选择左侧工具执行管理操作，生成结果将显示在此处</p>
          <div class="preview-tips">
            <div class="tip-item">
              <el-icon><Check /></el-icon>
              <span>一键生成农事建议、农情报告等专业内容</span>
            </div>
            <div class="tip-item">
              <el-icon><Check /></el-icon>
              <span>灾害评估与应急响应方案生成</span>
            </div>
            <div class="tip-item">
              <el-icon><Check /></el-icon>
              <span>数据异常排查与系统运行报告</span>
            </div>
          </div>
        </div>

        <template v-else-if="selectedResult">
          <div class="preview-header">
            <div class="preview-title-wrap">
              <span class="preview-chip" :class="'tone-' + selectedResult.tone">
                <el-icon><component :is="iconFor(selectedResult)" /></el-icon>{{ selectedResult.type }}
              </span>
              <div class="preview-heading">
                <span class="preview-title">{{ reportTitle(selectedResult.content, selectedResult.title) }}</span>
                <span class="preview-meta">
                  <span><el-icon><Location /></el-icon>{{ selectedResult.village }}</span>
                  <span class="meta-dot">·</span>
                  <span><el-icon><Clock /></el-icon>{{ fullTime(selectedResult.time) }}</span>
                </span>
              </div>
            </div>
            <div class="preview-actions">
              <el-button
                v-if="selectedResult.type === '生成农事建议'"
                type="primary"
                color="#2d6a4f"
                size="default"
                :loading="publishing"
                @click="openPublish(selectedResult)"
              >
                <el-icon v-if="!publishing"><Upload /></el-icon>{{ selectedResult.published ? '再次发布' : '发布到农事建议' }}
              </el-button>
              <el-button v-else plain size="default" @click="exportMarkdown(selectedResult)">
                <el-icon><Download /></el-icon>导出文档
              </el-button>
              <el-button class="preview-copy" plain size="default" @click="copyResult(selectedResult.content)">
                <el-icon><CopyDocument /></el-icon>复制内容
              </el-button>
            </div>
          </div>
          <!-- :key 让切换记录时重建正文，滚动位置自然回到顶部 -->
          <div
            :key="selectedResult.id"
            class="preview-content markdown-body"
            v-html="renderMarkdown(selectedResult.content)"
          />
        </template>

        <!-- 指令栏：固定在结果面板底部 -->
        <div class="command-bar">
          <el-icon class="command-prompt"><MagicStick /></el-icon>
          <el-input
            v-model="commandInput"
            placeholder="输入管理指令，如：分析本周气温变化趋势并给出农事调整建议"
            @keydown.enter="executeCommand"
            :disabled="loading"
            clearable
            size="default"
          />
          <kbd class="command-hint">Enter</kbd>
          <el-button
            type="primary"
            color="#2d6a4f"
            class="command-run"
            :loading="loading"
            :disabled="!commandInput.trim() || loading"
            @click="executeCommand"
          >
            <el-icon v-if="!loading"><Promotion /></el-icon>
            <span>{{ loading ? '执行中…' : '执行' }}</span>
          </el-button>
        </div>
      </div>
    </div>

    <!-- 发布农事建议确认弹窗 -->
    <el-dialog v-model="publishVisible" title="发布到农事建议" width="560px" append-to-body>
      <el-alert
        type="info" :closable="false" show-icon class="publish-alert"
        title="AI 生成的完整正文将作为建议内容发布，发布后可在「生产管理 → 农事建议」中编辑调整。"
      />
      <el-form :model="publishForm" label-width="80px">
        <el-form-item label="所属村镇">
          <el-select v-model="publishForm.village" placeholder="选择村镇" style="width: 100%">
            <el-option v-for="v in villages" :key="v.code" :label="v.name" :value="v.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="publishForm.title" maxlength="64" show-word-limit />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="优先级">
              <el-select v-model="publishForm.priorityTag" style="width: 100%">
                <el-option label="紧急（高）" value="高" />
                <el-option label="重要（中）" value="中" />
                <el-option label="一般（低）" value="低" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="图标">
              <el-select v-model="publishForm.iconKey" style="width: 100%">
                <el-option v-for="o in adviceIconOptions" :key="o.key" :label="o.label" :value="o.key" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="摘要">
          <el-input v-model="publishForm.summary" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button type="primary" color="#2d6a4f" :loading="publishing" @click="confirmPublish">确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ai-console-page {
  height: var(--ad-view-h, calc(100vh - 120px));
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  background: linear-gradient(180deg, #f5f9f6 0%, #edf3ef 100%);
}

.ai-console-page ::selection {
  background: rgba(63, 127, 82, 0.18);
}

/* ============ 顶部控制栏 ============ */
.console-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 18px;
  background: #fff;
  border: 1px solid #e6ece8;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(20, 40, 28, 0.04), 0 8px 22px rgba(20, 40, 28, 0.05);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.header-mark {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 17px;
  background: linear-gradient(150deg, #4c9468, #2d6a4f);
  box-shadow: 0 4px 10px rgba(45, 106, 79, 0.28);
}

.header-title {
  font-size: 16px;
  font-weight: 700;
  color: #203026;
  letter-spacing: 0.01em;
}

.header-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  color: #2f6240;
  background: #e7f2ea;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.village-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #687a70;
}

.village-label .el-icon {
  font-size: 14px;
}

.village-select {
  width: 158px;
}

/* ============ 主体区域 ============ */
.console-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
}

/* 左右两块面板的统一卡片语言 */
.tool-panel,
.preview-panel {
  background: #fff;
  border: 1px solid #e6ece8;
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(20, 40, 28, 0.04), 0 10px 28px rgba(20, 40, 28, 0.06);
}

/* 左侧工具面板 */
.tool-panel {
  width: 360px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.tool-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.tool-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
  padding: 0 16px;
}

.tool-tabs :deep(.el-tabs__item) {
  font-size: 13.5px;
  height: 46px;
  color: #687a70;
}

.tool-tabs :deep(.el-tabs__item.is-active) {
  font-weight: 600;
  color: #2f6240;
}

.tool-tabs :deep(.el-tabs__active-bar) {
  height: 2.5px;
  border-radius: 2px;
}

.tool-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: #eef2ee;
}

.tool-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px 16px;
  scrollbar-width: thin;
  scrollbar-color: rgba(47, 98, 64, 0.22) transparent;
}

.tool-tabs :deep(.el-tabs__content::-webkit-scrollbar) {
  width: 8px;
}

.tool-tabs :deep(.el-tabs__content::-webkit-scrollbar-thumb) {
  border-radius: 4px;
  border: 2px solid transparent;
  background-clip: content-box;
  background-color: rgba(47, 98, 64, 0.2);
}

.tool-tabs :deep(.el-tabs__content::-webkit-scrollbar-thumb:hover) {
  background-color: rgba(47, 98, 64, 0.34);
}

.tab-label {
  display: inline-flex;
  align-items: center;
}

.tab-count {
  margin-left: 6px;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: 999px;
  background: #e7f2ea;
  color: #2f6240;
  font-size: 11px;
  font-weight: 600;
  line-height: 17px;
  text-align: center;
}

/* ============ 管理工具 ============ */
.tool-category {
  margin-bottom: 16px;
}

.tool-category:last-child {
  margin-bottom: 0;
}

.category-label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  font-weight: 600;
  color: #7b8b81;
  margin-bottom: 9px;
  padding-left: 2px;
}

.category-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #52b788;
}

.tool-grid {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-card {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 12px;
  border-radius: 10px;
  cursor: pointer;
  border: 1px solid #e8eeea;
  background: #fff;
  outline: none;
  transition:
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.18s,
    background-color 0.18s,
    box-shadow 0.18s;
}

.tool-card:hover {
  border-color: #b7d6c2;
  background: #f6faf7;
  box-shadow: 0 6px 16px rgba(47, 98, 64, 0.1);
  transform: translateY(-1px);
}

.tool-card:active {
  transform: translateY(0);
}

.tool-card:focus-visible {
  box-shadow: 0 0 0 2px rgba(82, 183, 136, 0.45);
}

.tool-card.tool-disabled {
  cursor: not-allowed;
  opacity: 0.55;
  transform: none;
  box-shadow: none;
}

.tool-icon-tile {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  transition: transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.tool-card:hover .tool-icon-tile {
  transform: scale(1.06);
}

.tool-info {
  flex: 1;
  min-width: 0;
}

.tool-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #26382d;
  margin-bottom: 2px;
}

.tool-desc {
  font-size: 11.5px;
  color: #7b8b81;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-action {
  color: #b3bfb7;
  font-size: 15px;
  flex-shrink: 0;
  transition: transform 0.18s, color 0.18s;
}

.tool-card:hover .tool-action {
  color: #3f7f52;
  transform: translateX(2px);
}

.tool-action.is-loading {
  color: #3f7f52;
  animation: rotate 1s linear infinite;
}

@keyframes rotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* 分类色调（图标砖） */
.tone-green .tool-icon-tile,
.tool-icon-tile.tone-green {
  background: #e7f2ea;
  color: #2f7a4d;
}
.tone-amber .tool-icon-tile,
.tool-icon-tile.tone-amber {
  background: #fdf0dd;
  color: #b06a14;
}
.tone-teal .tool-icon-tile,
.tool-icon-tile.tone-teal {
  background: #e2f1ef;
  color: #207068;
}
.tone-slate .tool-icon-tile,
.tool-icon-tile.tone-slate {
  background: #edf1f3;
  color: #4d6170;
}

/* ============ 生成记录 ============ */
.results-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 48px 20px;
}

.results-empty-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #8fbd99;
  background: #f0f6f2;
  margin-bottom: 14px;
}

.results-empty-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: #46554d;
}

.results-empty-sub {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #8fa096;
}

.results-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 2px 10px;
  margin-bottom: 10px;
  border-bottom: 1px solid #eef2ee;
}

.results-count {
  font-size: 12px;
  color: #7b8b81;
}

.results-count strong {
  color: #2f6240;
  font-weight: 700;
}

.results-clear {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  font: inherit;
  font-size: 12px;
  color: #b0564e;
  cursor: pointer;
  transition: background-color 0.15s;
}

.results-clear:hover {
  background: #fdecec;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.result-item {
  position: relative;
  padding: 12px 13px 10px;
  border-radius: 11px;
  border: 1px solid #e8eeea;
  background: #fff;
  cursor: pointer;
  outline: none;
  transition:
    border-color 0.18s,
    background-color 0.18s,
    box-shadow 0.18s,
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
  animation: card-in 0.34s cubic-bezier(0.16, 1, 0.3, 1) backwards;
}

.result-item:hover {
  border-color: #b7d6c2;
  box-shadow: 0 6px 18px rgba(47, 98, 64, 0.1);
  transform: translateY(-1px);
}

.result-item:focus-visible {
  box-shadow: 0 0 0 2px rgba(82, 183, 136, 0.45);
}

.result-item.active {
  border-color: #8fbd99;
  background: #f5faf7;
  box-shadow: 0 0 0 1px rgba(63, 127, 82, 0.4), 0 8px 20px rgba(47, 98, 64, 0.1);
}

.result-item.active:hover {
  transform: none;
}

@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.result-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.result-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 62%;
  padding: 2.5px 9px;
  border-radius: 7px;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.5;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-chip .el-icon {
  font-size: 12.5px;
  flex-shrink: 0;
}

.result-time {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex-shrink: 0;
  font-size: 11px;
  color: #76857c;
  font-variant-numeric: tabular-nums;
}

.result-time .el-icon {
  font-size: 12px;
}

.result-title {
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
  color: #203026;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.result-excerpt {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #76857c;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.result-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 9px;
}

.result-village {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: 11.5px;
  color: #5d7568;
}

.result-village .el-icon {
  font-size: 12.5px;
  color: #8fbd99;
  flex-shrink: 0;
}

.result-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

.icon-btn {
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #93a399;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  transition: background-color 0.15s, color 0.15s;
}

.icon-btn:hover {
  background: #e7f2ea;
  color: #2f6240;
}

.icon-btn.danger:hover {
  background: #fdecec;
  color: #b0473f;
}

/* 记录卡片类型 chip 配色 */
.tone-green .result-chip,
.preview-chip.tone-green {
  background: #e7f2ea;
  color: #2f7a4d;
}
.tone-amber .result-chip,
.preview-chip.tone-amber {
  background: #fdf0dd;
  color: #a8620f;
}
.tone-teal .result-chip,
.preview-chip.tone-teal {
  background: #e2f1ef;
  color: #1f6e66;
}
.tone-slate .result-chip,
.preview-chip.tone-slate {
  background: #edf1f3;
  color: #4d6170;
}

/* ============ 右侧预览面板 ============ */
.preview-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.preview-loading {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
}

.preview-loading-icon {
  color: #3f7f52;
  animation: rotate 1s linear infinite;
}

.preview-loading-title {
  margin: 10px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: #26382d;
}

.preview-loading-desc {
  margin: 0;
  font-size: 12.5px;
  color: #7b8b81;
}

.skeleton-card {
  width: min(440px, 80%);
  margin-top: 26px;
  padding: 18px 20px;
  border: 1px solid #eef2ee;
  border-radius: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.sk {
  height: 12px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef3f0 25%, #f7faf8 37%, #eef3f0 63%);
  background-size: 400% 100%;
  animation: shimmer 1.5s ease-in-out infinite;
}

.sk-title {
  height: 16px;
  width: 46%;
}

.sk-line.w90 {
  width: 90%;
}
.sk-line.w70 {
  width: 70%;
}

@keyframes shimmer {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

.preview-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
}

.preview-empty-icon {
  width: 78px;
  height: 78px;
  border-radius: 20px;
  background: linear-gradient(150deg, #2d6a4f, #52b788);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
  box-shadow: 0 14px 30px rgba(45, 106, 79, 0.28);
}

.preview-empty-title {
  font-size: 18px;
  font-weight: 700;
  color: #203026;
  margin: 0 0 6px;
}

.preview-empty-desc {
  font-size: 13px;
  color: #7b8b81;
  margin: 0 0 22px;
}

.preview-tips {
  display: flex;
  flex-direction: column;
  gap: 9px;
  text-align: left;
}

.tip-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: #f4f8f5;
  border: 1px solid #e8eeea;
  font-size: 12.5px;
  color: #516358;
}

.tip-item .el-icon {
  color: #3f7f52;
  font-size: 14px;
}

.preview-header {
  flex-shrink: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-bottom: 1px solid #eef2ee;
  background: #fbfdfb;
}

.preview-title-wrap {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  min-width: 0;
}

.preview-chip {
  flex-shrink: 0;
  margin-top: 2px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.preview-heading {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.preview-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #203026;
  line-height: 1.4;
}

.preview-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11.5px;
  color: #85948b;
}

.preview-meta .el-icon {
  font-size: 12.5px;
  margin-right: 2px;
  vertical-align: -1.5px;
}

.meta-dot {
  color: #c2ccc6;
}

.preview-copy {
  flex-shrink: 0;
  margin-top: 1px;
}

.preview-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.icon-btn.publish {
  color: #2f7a4d;
}

.icon-btn.publish:hover {
  background: #e7f2ea;
  color: #1f5c38;
}

.publish-alert {
  margin-bottom: 16px;
}

.preview-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 22px 26px;
  font-size: 14px;
  line-height: 2;
  color: #33433a;
  word-break: break-word;
  scrollbar-width: thin;
  scrollbar-color: rgba(47, 98, 64, 0.22) transparent;
}

.preview-content::-webkit-scrollbar {
  width: 8px;
}

.preview-content::-webkit-scrollbar-thumb {
  border-radius: 4px;
  border: 2px solid transparent;
  background-clip: content-box;
  background-color: rgba(47, 98, 64, 0.2);
}

.preview-content::-webkit-scrollbar-thumb:hover {
  background-color: rgba(47, 98, 64, 0.34);
}

/* ============ Markdown 渲染样式 ============
   ⚠️ 正文是 v-html 注入的，不带 scoped 的 data-v 属性，
   所以后代选择器必须用 :deep() 包起来 —— 直接写 `.markdown-body h1` 编译成
   `.markdown-body h1[data-v-xxx]`，永远匹配不上，整块样式形同虚设
   （表现就是标题不绿、表格没有边框、只剩浏览器默认样式）。 */
.markdown-body :deep(:where(h1, h2, h3, h4)) {
  scroll-margin-top: 12px;
}
.markdown-body :deep(h1) {
  font-size: 22px;
  font-weight: 700;
  margin: 8px 0 14px;
  color: #203026;
  border-bottom: 2px solid #3f7f52;
  padding-bottom: 8px;
}
.markdown-body :deep(h2) {
  font-size: 18px;
  font-weight: 700;
  margin: 20px 0 10px;
  color: #2f6240;
}
.markdown-body :deep(h3) {
  font-size: 16px;
  font-weight: 600;
  margin: 16px 0 8px;
  color: #26382d;
}
.markdown-body :deep(h4) {
  font-size: 15px;
  font-weight: 600;
  margin: 12px 0 6px;
  color: #26382d;
}
.markdown-body :deep(p) {
  margin: 8px 0;
  line-height: 1.85;
}
.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  padding-left: 22px;
  margin: 8px 0;
}
.markdown-body :deep(li) {
  margin: 5px 0;
  line-height: 1.75;
}
.markdown-body :deep(li)::marker {
  color: #3f7f52;
}
.markdown-body :deep(strong) {
  color: #2f6240;
  font-weight: 700;
}
.markdown-body :deep(code) {
  background: #f0f5f2;
  padding: 2px 6px;
  border-radius: 5px;
  font-family: 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 13px;
  color: #b0473f;
}
.markdown-body :deep(pre) {
  background: #1d2e24;
  color: #e0eae3;
  padding: 16px;
  border-radius: 10px;
  overflow-x: auto;
  margin: 12px 0;
}
.markdown-body :deep(pre code) {
  background: none;
  color: inherit;
  padding: 0;
}

/* 表格：AI 报告里的作物清单主要靠它承载，所以做得实一点 ——
   斑马纹、首列加重、单元格上下对齐，扫读一列数字时不串行。 */
.markdown-body :deep(table) {
  width: 100%;
  margin: 14px 0;
  /* separate + border-spacing:0 才能让外层圆角真正裁掉四角（collapse 下圆角不生效） */
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  border: 1px solid #e2ede7;
  border-radius: 10px;
  overflow: hidden;
}
.markdown-body :deep(thead th) {
  background: #eef5f0;
  color: #2f6240;
  font-weight: 600;
  text-align: left;
  padding: 10px 12px;
  border-bottom: 1px solid #dbe7df;
  white-space: nowrap;
}
.markdown-body :deep(tbody td) {
  padding: 10px 12px;
  border-bottom: 1px solid #eef2ee;
  vertical-align: top;
  line-height: 1.7;
  color: #33433a;
}
.markdown-body :deep(tbody tr:nth-child(even) td) {
  background: #fbfdfb;
}
.markdown-body :deep(tbody tr:last-child td) {
  border-bottom: 0;
}
.markdown-body :deep(tbody td:first-child) {
  font-weight: 600;
  color: #26382d;
}
.markdown-body :deep(blockquote) {
  padding: 10px 14px;
  margin: 12px 0;
  border-radius: 8px;
  background: #f4f9f6;
  border: 1px solid #e2ede7;
  color: #4c5d53;
}
.markdown-body :deep(hr) {
  border: none;
  border-top: 1px solid #e6ece8;
  margin: 18px 0;
}
.markdown-body :deep(a) {
  color: #2f7a4d;
  text-decoration: underline;
  text-underline-offset: 2px;
}

/* ============ 底部指令栏 ============ */
.command-bar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 14px 14px;
  padding: 9px 10px 9px 15px;
  background: #fff;
  border: 1px solid #e2e9e4;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(45, 106, 79, 0.08);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.command-bar:focus-within {
  border-color: #8fbd99;
  box-shadow: 0 4px 18px rgba(82, 183, 136, 0.22);
}

.command-prompt {
  color: #52b788;
  font-size: 19px;
  flex-shrink: 0;
}

.command-bar .el-input {
  flex: 1;
  min-width: 0;
}

.command-bar .el-input :deep(.el-input__wrapper) {
  background: transparent;
  padding: 0;
  box-shadow: none !important;
}

.command-bar .el-input :deep(.el-input__inner) {
  color: #26382d;
  font-size: 13.5px;
}

.command-bar .el-input :deep(.el-input__inner::placeholder) {
  color: #a6b3aa;
}

.command-hint {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid #dde5df;
  background: #f6f9f7;
  color: #8fa096;
  font-family: inherit;
  font-size: 11px;
  line-height: 1.6;
}

.command-run {
  flex-shrink: 0;
}

/* ============ 窄屏适配：左右改为上下布局 ============ */
@media (max-width: 1024px) {
  .console-body {
    flex-direction: column;
  }
  .tool-panel {
    width: 100%;
    max-height: 42%;
  }
  .preview-panel {
    flex: 1;
  }
}

@media (max-width: 640px) {
  .ai-console-page {
    padding: 8px;
    gap: 8px;
  }
  .console-header {
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }
  .header-right {
    justify-content: flex-end;
  }
  .command-hint {
    display: none;
  }
  .preview-content {
    padding: 16px;
  }
}
</style>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getOperationLogsPaged, clearOperationLogs } from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { OperationLog } from '@/types/weather'

const logs = ref<OperationLog[]>([])
const loading = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)

const operatorFilter = ref('')
const moduleFilter = ref('')
const typeFilter = ref('')
const dateRange = ref<[string, string] | null>(null)

const moduleOptions = [
  { label: '认证', value: 'auth' },
  { label: '仪表盘', value: 'dashboard' },
  { label: '预警', value: 'warning' },
  { label: '天气', value: 'weather' },
  { label: '村镇', value: 'village' },
  { label: '作物', value: 'crop' },
  { label: '农事建议', value: 'farm' },
  { label: '灾害', value: 'disaster' },
  { label: '帮扶', value: 'assistance' },
  { label: '用户', value: 'user' },
  { label: '管理员', value: 'admin' },
  { label: 'AI对话', value: 'ai' },
  { label: '农情上报', value: 'farm-report' },
  { label: '反馈', value: 'feedback' },
]

const typeOptions = [
  { label: '查询', value: 'QUERY' },
  { label: '创建', value: 'CREATE' },
  { label: '更新', value: 'UPDATE' },
  { label: '删除', value: 'DELETE' },
  { label: '登录', value: 'LOGIN' },
  { label: 'AI对话', value: 'CHAT' },
]

function typeLabel(t: string): string {
  const map: Record<string, string> = {
    QUERY: '查询', CREATE: '创建', UPDATE: '更新',
    DELETE: '删除', LOGIN: '登录', CHAT: 'AI对话',
  }
  return map[t] || t
}

function typeTagType(t: string): string {
  // 空字符串会被 Element Plus 的 prop 校验拒绝并刷警告，默认色显式写 'primary'
  const map: Record<string, string> = {
    QUERY: 'primary', CREATE: 'success', UPDATE: 'warning',
    DELETE: 'danger', LOGIN: 'info', CHAT: 'primary',
  }
  return map[t] || 'primary'
}

function moduleLabel(m: string): string {
  const map: Record<string, string> = {
    auth: '认证', dashboard: '仪表盘', warning: '预警',
    weather: '天气', village: '村镇', crop: '作物',
    farm: '农事建议', disaster: '灾害', assistance: '帮扶',
    user: '用户', admin: '管理员', ai: 'AI对话',
    'farm-report': '农情上报', feedback: '反馈', system: '系统', log: '日志',
  }
  return map[m] || m
}

function formatTime(t: string | null): string {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

async function loadData() {
  loading.value = true
  try {
    const params: any = { page: currentPage.value - 1, size: pageSize.value }
    if (operatorFilter.value) params.operator = operatorFilter.value
    if (moduleFilter.value) params.module = moduleFilter.value
    if (typeFilter.value) params.type = typeFilter.value
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const result = await getOperationLogsPaged(params)
    logs.value = result.content
    total.value = result.totalElements
  } catch {
    ElMessage.error('加载操作日志失败')
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadData()
}

function handleSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  loadData()
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}

function handleReset() {
  operatorFilter.value = ''
  moduleFilter.value = ''
  typeFilter.value = ''
  dateRange.value = null
  currentPage.value = 1
  loadData()
}

function handleExport() {
  if (logs.value.length === 0) {
    ElMessage.warning('没有可导出的日志数据')
    return
  }
  const headers = ['ID', '操作人', '操作类型', '模块', 'IP', '描述', '状态', '操作时间']
  const rows = logs.value.map(r => [
    r.id,
    r.operatorName || '',
    typeLabel(r.operationType),
    moduleLabel(r.module),
    r.ip || '',
    (r.description || '').replace(/"/g, '""'),
    r.status === 1 ? '成功' : '失败',
    formatTime(r.createdAt),
  ])
  const csvContent = '\uFEFF' + [headers, ...rows].map(row => row.map(c => `"${c}"`).join(',')).join('\n')
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `操作日志_${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(link.href)
  ElMessage.success('导出成功')
}

async function handleDeleteLogs() {
  try {
    await ElMessageBox.confirm(
      '确定清空所有操作日志？此操作不可恢复。',
      '清空日志',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await clearOperationLogs()
    ElMessage.success('日志已清空')
    loadData()
  } catch { /* cancel */ }
}

let refreshTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadData()
  refreshTimer = setInterval(() => loadData(), 30000)
})

onUnmounted(() => {
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null }
})
</script>

<template>
  <el-card shadow="hover">
    <template #header>
      <div class="card-header">
        <span>操作日志</span>
        <div class="header-actions">
          <el-button size="small" @click="handleExport">导出CSV</el-button>
        </div>
      </div>
    </template>

    <div class="filter-bar">
      <el-input
        v-model="operatorFilter"
        placeholder="搜索操作人"
        clearable
        style="width: 160px"
        prefix-icon="Search"
        @clear="handleSearch"
        @keyup.enter="handleSearch"
      />
      <el-select v-model="moduleFilter" placeholder="模块筛选" clearable style="width: 120px" @change="handleSearch">
        <el-option v-for="opt in moduleOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-select v-model="typeFilter" placeholder="操作类型" clearable style="width: 120px" @change="handleSearch">
        <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 240px"
        @change="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-button @click="handleReset">重置</el-button>
      <span class="filter-count">共 {{ total }} 条记录</span>
    </div>

    <el-table :data="logs" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="operatorName" label="操作人" width="100" />
      <el-table-column label="操作类型" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="typeTagType(row.operationType)" size="small">{{ typeLabel(row.operationType) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模块" width="100" align="center">
        <template #default="{ row }">{{ moduleLabel(row.module) }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="130" />
      <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作时间" width="165">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handleSizeChange"
        @current-change="handlePageChange"
      />
    </div>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filter-count {
  font-size: 13px;
  color: #6B7280;
  margin-left: auto;
}
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

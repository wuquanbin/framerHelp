<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  getAppConfigs,
  updateAppConfig,
  createAppConfig,
  deleteAppConfig,
  getSystemHealth,
} from '@/api/admin'
import type { SystemHealth } from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { AppConfig } from '@/types/weather'

const configs = ref<AppConfig[]>([])
const loading = ref(false)
const editDialogVisible = ref(false)
const addDialogVisible = ref(false)

/** 系统运行状况：后端真查一次返回；取不到就是 null —— 不允许再显示假的「正常」 */
const health = ref<SystemHealth | null>(null)

const editForm = ref<AppConfig>({ id: 0, configKey: '', configValue: '', description: '', category: '' })
const addForm = ref({ configKey: '', configValue: '', description: '', category: '自定义' })

const categories = computed(() => {
  const cats = new Set(configs.value.map(c => c.category))
  return Array.from(cats)
})

const groupedConfigs = computed(() => {
  const groups: Record<string, AppConfig[]> = {}
  for (const c of configs.value) {
    const bucket = groups[c.category] ?? (groups[c.category] = [])
    bucket.push(c)
  }
  return groups
})

const categoryIcon: Record<string, string> = {
  '基本信息': 'Monitor',
  '天气同步': 'Cloudy',
  '预警通知': 'Bell',
  'AI助手': 'ChatDotRound',
  '自定义': 'Setting',
}

async function loadConfigs() {
  loading.value = true
  try {
    configs.value = await getAppConfigs()
  } catch {
    ElMessage.error('加载系统配置失败')
  } finally {
    loading.value = false
  }
}

async function loadHealth() {
  try {
    health.value = await getSystemHealth()
  } catch {
    // 取不到就留 null，页面显示「未获取」—— 以前这里是写死的「正常」，
    // 服务挂了也照样绿，属于假状态。
    health.value = null
  }
}

/** 运行时长：按天/小时/分钟三档，避免显示成一长串秒数 */
const uptimeText = computed(() => {
  const s = health.value?.uptimeSeconds ?? 0
  if (!s) return '—'
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d} 天 ${h} 小时`
  if (h > 0) return `${h} 小时 ${m} 分`
  return `${m} 分钟`
})

const memoryText = computed(() => {
  const h = health.value
  return h ? `${h.jvmUsedMb} / ${h.jvmMaxMb} MB` : '—'
})

const syncText = computed(() => health.value?.weatherLastSyncAt ?? '暂无数据')

/**
 * 配置键 → 生效方式。
 * 以前这一列根本没有，用户改完以为生效了，其实后台没人读这张表。
 * 现在把「什么时候生效」写在脸上，没接运行时的一律标成「暂未接入」。
 */
const EFFECT: Record<string, string> = {
  'weather.sync.enabled': '立即生效',
  'weather.sync.interval': '下次调度生效',
  'warning.push.enabled': '立即生效',
  'farm.advice.push.time': '次日生效',
  'ai.model': '立即生效',
  'ai.temperature': '立即生效',
  'ai.max.tokens': '立即生效',
}
function effectOf(key: string) {
  return EFFECT[key] ?? '暂未接入运行时'
}
function effectTagType(key: string): 'success' | 'info' {
  return effectOf(key) === '暂未接入运行时' ? 'info' : 'success'
}

function handleEdit(config: AppConfig) {
  editForm.value = { ...config }
  editDialogVisible.value = true
}

async function saveEdit() {
  try {
    await updateAppConfig(editForm.value.id, {
      configValue: editForm.value.configValue,
      description: editForm.value.description,
    })
    ElMessage.success('配置已保存')
    editDialogVisible.value = false
    loadConfigs()
  } catch {
    ElMessage.error('保存失败')
  }
}

function handleAdd() {
  addForm.value = { configKey: '', configValue: '', description: '', category: '自定义' }
  addDialogVisible.value = true
}

async function saveAdd() {
  if (!addForm.value.configKey || !addForm.value.configValue) {
    ElMessage.warning('配置键和值不能为空')
    return
  }
  try {
    await createAppConfig(addForm.value)
    ElMessage.success('配置已添加')
    addDialogVisible.value = false
    loadConfigs()
  } catch {
    ElMessage.error('添加失败')
  }
}

async function handleDelete(config: AppConfig) {
  try {
    await ElMessageBox.confirm(
      `确定删除配置项「${config.configKey}」？`,
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteAppConfig(config.id)
    ElMessage.success('已删除')
    loadConfigs()
  } catch { /* cancel */ }
}

onMounted(() => {
  loadConfigs()
  loadHealth()
})
</script>

<template>
  <div class="system-settings" v-loading="loading">
    <!-- 系统状态概览 -->
    <el-card shadow="hover" class="status-card">
      <template #header>系统状态</template>
      <!-- 六项全部来自 /admin/system/health：服务、数据库真检测，其余真读数 -->
      <div class="status-grid">
        <div class="status-item">
          <div class="status-value">
            <el-tag :type="health ? (health.service === 'UP' ? 'success' : 'danger') : 'info'" size="small">
              {{ health ? (health.service === 'UP' ? '运行中' : '异常') : '未获取' }}
            </el-tag>
          </div>
          <div class="status-label">服务状态</div>
        </div>
        <div class="status-item">
          <div class="status-value">
            <el-tag :type="health ? (health.database === 'UP' ? 'success' : 'danger') : 'info'" size="small">
              {{ health ? (health.database === 'UP' ? '连接正常' : '连接异常') : '未获取' }}
            </el-tag>
          </div>
          <div class="status-label">数据库</div>
        </div>
        <div class="status-item">
          <div class="status-value">{{ uptimeText }}</div>
          <div class="status-label">运行时长</div>
        </div>
        <div class="status-item">
          <div class="status-value">{{ memoryText }}</div>
          <div class="status-label">JVM 内存</div>
        </div>
        <div class="status-item">
          <div class="status-value status-value-sm">{{ syncText }}</div>
          <div class="status-label">天气最后同步</div>
        </div>
        <div class="status-item">
          <div class="status-value status-value-sm">{{ health?.version ?? '—' }}</div>
          <div class="status-label">系统版本</div>
        </div>
      </div>
    </el-card>

    <!-- 配置项列表 -->
    <el-card shadow="hover" class="config-card">
      <template #header>
        <div class="card-header">
          <span>系统配置</span>
          <el-button type="primary" size="small" @click="handleAdd">新增配置</el-button>
        </div>
      </template>

      <div v-for="category in categories" :key="category" class="config-group">
        <div class="group-title">{{ category }}</div>
        <el-table :data="groupedConfigs[category]" border stripe size="default">
          <el-table-column prop="configKey" label="配置项" min-width="200">
            <template #default="{ row }">
              <span class="config-key">{{ row.configKey }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="configValue" label="当前值" min-width="150">
            <template #default="{ row }">
              <template v-if="row.configValue === 'true'">
                <el-tag type="success" size="small">已开启</el-tag>
              </template>
              <template v-else-if="row.configValue === 'false'">
                <el-tag type="danger" size="small">已关闭</el-tag>
              </template>
              <template v-else>
                <span class="config-value">{{ row.configValue }}</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="180" />
          <el-table-column label="生效方式" width="140" align="center">
            <template #default="{ row }">
              <el-tag :type="effectTagType(row.configKey)" size="small" effect="plain">
                {{ effectOf(row.configKey) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" align="center">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="handleEdit(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <!-- 编辑弹窗 -->
    <el-dialog v-model="editDialogVisible" title="编辑配置" width="480px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="配置项">
          <el-input :model-value="editForm.configKey" disabled />
        </el-form-item>
        <el-form-item label="配置值">
          <template v-if="editForm.configValue === 'true' || editForm.configValue === 'false'">
            <el-switch
              v-model="editForm.configValue"
              active-value="true"
              inactive-value="false"
              active-text="开启"
              inactive-text="关闭"
            />
          </template>
          <template v-else>
            <el-input v-model="editForm.configValue" />
          </template>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增弹窗 -->
    <el-dialog v-model="addDialogVisible" title="新增配置" width="480px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="配置键">
          <el-input v-model="addForm.configKey" placeholder="如 app.custom.setting" />
        </el-form-item>
        <el-form-item label="配置值">
          <el-input v-model="addForm.configValue" placeholder="配置值" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="addForm.description" placeholder="配置项说明" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="addForm.category" allow-create filterable placeholder="选择或输入分类">
            <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
            <el-option label="自定义" value="自定义" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveAdd">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.system-settings {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.status-card .status-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  text-align: center;
}

/* 六个模块状态卡在窄屏会被挤成一条（每格只剩十几 px），
   按屏宽降到 3 列 / 2 列 —— 与看板页的断点保持一致 */
@media (max-width: 1200px) {
  .status-card .status-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 700px) {
  .status-card .status-grid { grid-template-columns: repeat(2, 1fr); }
}

.status-item {
  padding: 12px 8px;
}

.status-value {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}

/* 日期、版本号这类长文本用 20px 会把六宫格撑变形，降一档 */
.status-value-sm {
  font-size: 14px;
}

.status-label {
  font-size: 13px;
  color: #6B7280;
}

.config-card .card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.config-group {
  margin-bottom: 20px;
}

.config-group:last-child {
  margin-bottom: 0;
}

.group-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
}

.config-key {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 13px;
  color: #606266;
}

.config-value {
  font-weight: 500;
  color: #303133;
}
</style>

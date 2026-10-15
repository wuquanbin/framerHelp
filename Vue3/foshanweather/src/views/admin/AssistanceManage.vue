<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  getAdminAssistanceRecordsPaged,
  createAssistanceRecord,
  updateAssistanceRecord,
  deleteAssistanceRecord,
  getAdminVillages,
  getAdminFarmReportsPaged,
} from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import { canDelete } from '@/utils/roles'
import ReadOnlyNote from '@/components/ReadOnlyNote.vue'
import { ASSISTANCE_TYPES, ASSISTANCE_STATUS, buildLedgerText, assistanceTypeOf } from '@/utils/assistance'
import type { AssistanceRecord, FarmReport } from '@/types/weather'

const router = useRouter()
// 村镇管理员不可删除台账（后端同样会 403）
const adminStore = useAdminStore()

const records = ref<AssistanceRecord[]>([])
const villages = ref<Array<{ code: string; name: string }>>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const form = ref<any>({
  assistanceType: '', description: '', status: '进行中',
})

// 手工补录：候选农情上报（最近 50 条，本地筛选）
const reportOptions = ref<FarmReport[]>([])
const reportOptionsLoading = ref(false)
const selectedReportId = ref<number | null>(null)

// ========== 分页 ==========
// el-pagination 的 current-page 是 1 起始，后端 page 是 0 起始，请求时减 1。
const page = ref(1)
const size = ref(20)
const total = ref(0)

// 帮扶类型与上报类型映射统一取自 @/utils/assistance（农情上报页登记台账用的是同一份，
// 以前这里漏了 other，两条入口对同一条上报会预填出不同类型）
const assistanceTypes = ASSISTANCE_TYPES
const assistanceStatus = ASSISTANCE_STATUS

onMounted(() => {
  loadData()
  loadVillages()
})

async function loadData() {
  loading.value = true
  try {
    const paged = await getAdminAssistanceRecordsPaged({ page: page.value - 1, size: size.value })
    records.value = paged.items as AssistanceRecord[]
    total.value = paged.total
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadVillages() {
  try {
    villages.value = await getAdminVillages()
  } catch {
    // 村镇名仅用于展示，拉取失败时表格退回显示编码
  }
}

function villageName(code?: string | null): string {
  if (!code) return '未归属'
  return villages.value.find(v => v.code === code)?.name || code
}

function formatTime(t?: string | null): string {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

function handlePageChange(p: number) {
  page.value = p
  loadData()
}

async function openCreate() {
  isEdit.value = false
  selectedReportId.value = null
  form.value = { assistanceType: '', description: '', status: '已完成' }
  dialogVisible.value = true
  if (reportOptions.value.length === 0) {
    reportOptionsLoading.value = true
    try {
      const paged = await getAdminFarmReportsPaged({ page: 0, size: 50 })
      reportOptions.value = paged.items
    } catch {
      ElMessage.error('农情上报列表加载失败')
    } finally {
      reportOptionsLoading.value = false
    }
  }
}

/** 台账事由：描述已含标题时不重复拼接，限长 250 */
/** 选中关联上报后，按上报内容预填台账类型与描述 */
function onReportChange(reportId: number | null) {
  const report = reportOptions.value.find(r => r.id === reportId)
  if (!report) return
  form.value.assistanceType = assistanceTypeOf(report.reportType)
  form.value.description = buildLedgerText(report.title, report.description)
}

function selectedReport(): FarmReport | undefined {
  return reportOptions.value.find(r => r.id === selectedReportId.value)
}

function openEdit(row: AssistanceRecord) {
  isEdit.value = true
  selectedReportId.value = row.reportId ?? null
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  if (!isEdit.value) {
    if (!selectedReportId.value) {
      ElMessage.warning('请先选择关联农情上报')
      return
    }
    if (!form.value.assistanceType) {
      ElMessage.warning('请选择帮扶类型')
      return
    }
  }
  if (!form.value.description?.trim()) {
    ElMessage.warning('请填写帮扶内容')
    return
  }
  submitting.value = true
  try {
    if (isEdit.value) {
      await updateAssistanceRecord(form.value.id, form.value)
    } else {
      await createAssistanceRecord({
        reportId: selectedReportId.value,
        assistanceType: form.value.assistanceType,
        description: form.value.description,
        status: form.value.status,
      })
    }
    ElMessage.success(isEdit.value ? '更新成功' : '台账已登记')
    dialogVisible.value = false
    loadData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(id: number) {
  // 点"取消"时 confirm 会 reject，单独捕获后直接返回
  try { await ElMessageBox.confirm('确定删除该帮扶记录？', '提示', { type: 'warning' }) } catch { return }
  try { await deleteAssistanceRecord(id); ElMessage.success('已删除'); loadData() } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '删除失败') }
}

/** 从关联农情列跳到农情管理并自动打开该上报详情 */
function gotoReport(reportId: number) {
  router.push({ path: '/admin/farm-reports', query: { reportId: String(reportId) } })
}

function statusType(s: string) {
  if (s === '已完成') return 'success'
  if (s === '进行中') return 'primary'
  if (s === '待跟进') return 'warning'
  return 'info'
}
</script>

<template>
  <el-card shadow="hover">
    <template #header>
      <div class="card-header">
        <span>帮扶台账管理</span>
        <el-button plain type="primary" @click="openCreate">手工补录</el-button>
      </div>
    </template>

    <el-alert type="info" :closable="false" show-icon class="track-tip">
      <template #title>
        台账为帮扶结案记录，计入帮扶成效看板：农情上报标记「已解决」时自动建档，漏登记可用「手工补录」关联上报；
        过程沟通请记在「农情管理 → 处理沟通」，不计入台账。
      </template>
    </el-alert>

    <ReadOnlyNote
      v-if="!canDelete(adminStore.role)"
      text="当前账号可以补录和修改帮扶记录；删除需要超级管理员或专家权限。"
    />
    <el-table :data="records" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column label="关联农情" min-width="210">
        <template #default="{ row }">
          <div v-if="row.reportId && row.reportTitle" class="report-cell" @click="gotoReport(row.reportId)">
            <span class="report-id">#{{ row.reportId }}</span>
            <span class="report-title" :title="row.reportTitle">{{ row.reportTitle }}</span>
          </div>
          <span v-else-if="row.reportId" class="report-gone"> #{{ row.reportId }} 上报已删除</span>
          <el-tag v-else type="info" size="small" effect="plain">手工登记</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="农户" width="100">
        <template #default="{ row }">{{ row.user?.nickname || '—' }}</template>
      </el-table-column>
      <el-table-column label="村镇" width="96">
        <template #default="{ row }">{{ villageName(row.user?.villageCode) }}</template>
      </el-table-column>
      <el-table-column prop="assistanceType" label="帮扶类型" width="110" />
      <el-table-column prop="description" label="帮扶内容" show-overflow-tooltip min-width="220" />
      <el-table-column label="状态" width="88" align="center">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="登记时间" width="150">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canDelete(adminStore.role)" type="danger" text size="small" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 服务端分页器：total 来自后端 -->
    <div class="pagination-bar">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        :current-page="page"
        :page-size="size"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="(s: number) => { size = s; page = 1; loadData() }"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑帮扶记录' : '手工补录帮扶台账'" width="600px">
    <el-alert
      v-if="!isEdit"
      type="warning"
      :closable="false"
      show-icon
      class="form-tip"
      title="台账必须关联一条农情上报，农户与村镇由上报自动带出；重复登记同一上报会被拦截。"
    />
    <el-form :model="form" label-width="90px">
      <el-form-item v-if="!isEdit" label="关联农情">
        <el-select
          v-model="selectedReportId"
          filterable
          placeholder="选择要补录台账的农情上报"
          :loading="reportOptionsLoading"
          style="width: 100%"
          @change="onReportChange"
        >
          <el-option
            v-for="r in reportOptions"
            :key="r.id"
            :value="r.id"
            :label="`#${r.id} ${r.title}（${r.nickname || '未知农户'}·${villageName(r.villageCode)}）`"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!isEdit && selectedReport()" label="农户/村镇">
        <span class="text-muted">{{ selectedReport()?.nickname || '未知农户' }} ｜ {{ villageName(selectedReport()?.villageCode) }}</span>
      </el-form-item>
      <el-form-item label="帮扶类型">
        <el-select v-model="form.assistanceType" style="width: 100%">
          <el-option v-for="t in assistanceTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="帮扶内容"><el-input v-model="form.description" type="textarea" :rows="4" maxlength="250" show-word-limit /></el-form-item>
      <el-form-item label="状态">
        <el-select v-model="form.status" style="width: 100%">
          <el-option v-for="s in assistanceStatus" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.track-tip { margin-bottom: 14px; }
.form-tip { margin-bottom: 14px; }
.text-muted { color: #9ca3af; font-size: 13px; }

/* 关联农情单元格：编号 + 上报标题双行，整格可点 */
.report-cell {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 3px 6px;
  margin: 0 -6px;
  border-radius: 6px;
}
.report-cell:hover { background: #f0f7f3; }
.report-id { color: #2d6a4f; font-weight: 600; font-size: 13px; line-height: 1.3; }
.report-cell:hover .report-id { text-decoration: underline; }
.report-title {
  font-size: 12px;
  color: #5f6b63;
  line-height: 1.3;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.report-cell:hover .report-title { color: #2d6a4f; }
.report-gone { font-size: 12px; color: #a6b3aa; }
</style>

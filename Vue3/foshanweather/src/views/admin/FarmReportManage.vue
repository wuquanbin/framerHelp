<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminFarmReportsPaged, updateFarmReportStatus, deleteFarmReport, getAdminFarmResponses, deleteFarmResponse, addFarmResponse, createAssistanceRecord, getAdminFarmReportDetail } from '@/api/admin'
import { useNoticeStore } from '@/stores/notice'
import { useAdminStore } from '@/stores/admin'
import { canDelete } from '@/utils/roles'
import ReadOnlyNote from '@/components/ReadOnlyNote.vue'
import { ASSISTANCE_TYPES, ASSISTANCE_STATUS, buildLedgerDraft } from '@/utils/assistance'
import type { FarmReport, FarmResponse } from '@/types/weather'

const route = useRoute()
const reports = ref<FarmReport[]>([])
const responses = ref<FarmResponse[]>([])
const loading = ref(false)
const detailVisible = ref(false)
const responseVisible = ref(false)
const selectedReport = ref<FarmReport | null>(null)
const statusFilter = ref('')
const typeFilter = ref('')
// 提交处理记录时的按钮 loading，防止重复点击产生多条记录
const submitting = ref(false)
// 帮扶台账登记弹窗
const ledgerVisible = ref(false)
const ledgerSubmitting = ref(false)
const ledgerForm = ref({ assistanceType: '综合帮扶', description: '', status: '已完成' })
// 筛选监听在首屏路由预选完成后才生效，避免初始化时重复请求
const filtersReady = ref(false)
// 全局待办角标 store（处理上报后即时刷新侧栏角标）
const noticeStore = useNoticeStore()
// 村镇管理员没有删除权（后端 SecurityConfig 对 DELETE /api/admin/** 只放行超级管理员+专家）
const adminStore = useAdminStore()

// ========== 分页 ==========
// 状态/类型筛选与翻页都由后端处理，保证数据量大时翻页与筛选仍然正确。
// el-pagination 的 current-page 从 1 起始，后端 page 从 0 起始，请求时减 1。
const page = ref(1)
const size = ref(20)
const total = ref(0)

const responseForm = ref({
  responderOpenid: 'admin',
  responderName: '',
  responseType: 'guidance',
  content: ''
})

const typeLabels: Record<string, string> = {
  pest: '病虫害', disaster: '灾损', growth: '长势', help: '求助', other: '其他'
}
const statusLabels: Record<string, string> = {
  pending: '待处理', processing: '处理中', resolved: '已解决', closed: '已关闭'
}
const urgencyLabels: Record<string, string> = {
  low: '一般', medium: '较急', high: '紧急'
}
const responseTypeLabels: Record<string, string> = {
  reply: '回复', guidance: '技术指导', dispatch: '派人处理', close: '关闭'
}

// 帮扶类型/状态与预填逻辑统一放 @/utils/assistance，台账页用的是同一份
const assistanceTypes = ASSISTANCE_TYPES
const assistanceStatus = ASSISTANCE_STATUS

const villageOptions = [
  { label: '白坭镇', value: 'baini' },
  { label: '更合镇', value: 'genghe' },
  { label: '芦苞镇', value: 'lubao' },
  { label: '里水镇', value: 'lishui' },
  { label: '陈村镇', value: 'chencun' },
]

function villageName(code: string): string {
  return villageOptions.find(d => d.value === code)?.label || code || '--'
}

function formatTime(t: string | null): string {
  if (!t) return '--'
  return new Date(t).toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/** 统一提取后端返回的真实错误信息，取不到时用默认文案兜底 */
function errText(e: any, fallback: string): string {
  return e?.response?.data?.message || e?.message || fallback
}

async function loadReports() {
  loading.value = true
  try {
    const paged = await getAdminFarmReportsPaged({
      page: page.value - 1, // 前端 1 起始 → 后端 0 起始
      size: size.value,
      status: statusFilter.value || undefined,
      type: typeFilter.value || undefined,
    })
    reports.value = paged.items
    total.value = paged.total
  } catch (e) {
    ElMessage.error(errText(e, '上报列表加载失败'))
  } finally {
    loading.value = false
  }
}

// 改筛选条件时回到第 1 页（否则可能停在超出范围的页码上看不到数据）
watch([statusFilter, typeFilter], () => {
  if (!filtersReady.value) return
  page.value = 1
  loadReports()
})

function handlePageChange(p: number) {
  page.value = p
  loadReports()
}

async function openDetail(report: FarmReport) {
  selectedReport.value = report
  detailVisible.value = true
  await loadResponses(report.id)
}

async function loadResponses(reportId: number) {
  try {
    responses.value = await getAdminFarmResponses(reportId)
  } catch (e) {
    ElMessage.error(errText(e, '处理沟通加载失败'))
  }
}

async function updateStatus(report: FarmReport, newStatus: string) {
  try {
    await updateFarmReportStatus(report.id, newStatus)
    ElMessage.success('状态已更新')
    loadReports()
    noticeStore.fetchCounts()
  } catch (e) {
    ElMessage.error(errText(e, '状态更新失败'))
  }
}

async function handleDeleteReport(report: FarmReport) {
  // 确认框点"取消"会 reject，单独捕获后直接返回，避免未捕获异常
  try {
    await ElMessageBox.confirm(
      `确定删除上报「${report.title}」？其下处理沟通记录会一并删除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  try {
    await deleteFarmReport(report.id)
    ElMessage.success('已删除')
    // 若删掉的正是详情弹窗中的记录，关闭弹窗
    if (selectedReport.value?.id === report.id) detailVisible.value = false
    loadReports()
    noticeStore.fetchCounts()
  } catch (e) {
    ElMessage.error(errText(e, '删除失败'))
  }
}

function openAddResponse(report: FarmReport) {
  selectedReport.value = report
  responseForm.value = {
    responderOpenid: 'admin',
    responderName: '',
    responseType: 'guidance',
    content: ''
  }
  responseVisible.value = true
}

async function submitResponse() {
  if (!selectedReport.value) return
  if (!responseForm.value.content.trim()) {
    ElMessage.warning('请输入沟通内容')
    return
  }
  if (responseForm.value.content.length > 1000) {
    ElMessage.warning('沟通内容不能超过 1000 字')
    return
  }
  submitting.value = true
  try {
    // 处理人留空时给默认名称，避免时间线里显示空白
    const payload = {
      ...responseForm.value,
      responderName: responseForm.value.responderName.trim() || '管理员'
    }
    await addFarmResponse(selectedReport.value.id, payload)
    ElMessage.success('处理记录已添加')
    responseVisible.value = false
    loadReports()
    noticeStore.fetchCounts()
    // 详情弹窗正打开且是同一条上报时，同步刷新沟通时间线
    if (detailVisible.value) {
      loadResponses(selectedReport.value.id)
    }
  } catch (e) {
    ElMessage.error(errText(e, '处理记录提交失败'))
  } finally {
    submitting.value = false
  }
}

async function handleDeleteResponse(resp: FarmResponse) {
  try {
    await ElMessageBox.confirm('确定删除这条处理记录？', '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      confirmButtonClass: 'el-button--danger'
    })
  } catch { return }
  try {
    await deleteFarmResponse(resp.id)
    ElMessage.success('已删除')
    if (selectedReport.value) loadResponses(selectedReport.value.id)
  } catch (e) {
    ElMessage.error(errText(e, '删除失败'))
  }
}

// ========== 登记帮扶台账（结案记录，计入帮扶成效看板） ==========

function openLedger(report: FarmReport) {
  selectedReport.value = report
  ledgerForm.value = buildLedgerDraft(report)
  ledgerVisible.value = true
}

async function submitLedger() {
  if (!selectedReport.value) return
  if (!ledgerForm.value.description.trim()) {
    ElMessage.warning('请填写帮扶内容')
    return
  }
  ledgerSubmitting.value = true
  try {
    await createAssistanceRecord({
      reportId: selectedReport.value.id,
      assistanceType: ledgerForm.value.assistanceType,
      description: ledgerForm.value.description,
      status: ledgerForm.value.status,
    })
    ElMessage.success('帮扶台账已登记')
    ledgerVisible.value = false
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '台账登记失败')
  } finally {
    ledgerSubmitting.value = false
  }
}

onMounted(async () => {
  // 支持从待办卡/台账页带 query 跳转：status 预选状态，reportId 自动打开详情
  const q = route.query
  if (typeof q.status === 'string' && statusLabels[q.status]) {
    statusFilter.value = q.status
  }
  await loadReports()
  filtersReady.value = true
  if (typeof q.reportId === 'string') {
    const rid = Number(q.reportId)
    if (Number.isFinite(rid) && rid > 0) {
      let target = reports.value.find(r => r.id === rid)
      if (!target) {
        try {
          target = await getAdminFarmReportDetail(rid)
        } catch {
          ElMessage.error('该农情上报不存在或已删除')
        }
      }
      if (target) openDetail(target)
    }
  }
})
</script>

<template>
  <el-card shadow="hover">
    <template #header>农情管理</template>

    <div class="filter-bar">
      <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width: 130px">
        <el-option label="待处理" value="pending" />
        <el-option label="处理中" value="processing" />
        <el-option label="已解决" value="resolved" />
        <el-option label="已关闭" value="closed" />
      </el-select>
      <el-select v-model="typeFilter" placeholder="类型筛选" clearable style="width: 130px">
        <el-option label="病虫害" value="pest" />
        <el-option label="灾损" value="disaster" />
        <el-option label="长势" value="growth" />
        <el-option label="求助" value="help" />
        <el-option label="其他" value="other" />
      </el-select>
      <span class="filter-count">共 {{ total }} 条上报</span>
      <!-- 数据范围提示：村镇管理员看到的是后端按绑定村镇过滤后的结果，
           不写出来会被误以为「系统漏数据」 -->
      <el-tag v-if="adminStore.role === 'VILLAGE_ADMIN'" type="success" size="small" effect="plain">
        数据范围：{{ villageName(adminStore.villageCode) }}
      </el-tag>
    </div>

    <ReadOnlyNote
      v-if="!canDelete(adminStore.role)"
      text="当前账号可以受理、沟通并处置农情上报；删除上报记录需要超级管理员或专家权限。"
    />
    <el-table :data="reports" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
      <el-table-column label="类型" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.reportType === 'pest' ? 'danger' : row.reportType === 'disaster' ? 'warning' : row.reportType === 'help' ? 'primary' : 'success'" size="small">
            {{ typeLabels[row.reportType] || row.reportType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上报人" width="100">
        <template #default="{ row }">{{ row.nickname || '--' }}</template>
      </el-table-column>
      <el-table-column label="村镇" width="90">
        <template #default="{ row }">{{ villageName(row.villageCode) }}</template>
      </el-table-column>
      <el-table-column prop="cropName" label="作物" width="80">
        <template #default="{ row }">{{ row.cropName || '--' }}</template>
      </el-table-column>
      <el-table-column label="紧急" width="70" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.urgency === 'high'" type="danger" size="small">紧急</el-tag>
          <el-tag v-else-if="row.urgency === 'medium'" type="warning" size="small">较急</el-tag>
          <span v-else class="text-muted">一般</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'pending' ? 'warning' : row.status === 'processing' ? 'primary' : 'success'" size="small">
            {{ statusLabels[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="140">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
          <el-button link type="success" size="small" @click="openAddResponse(row)">沟通</el-button>
          <el-button v-if="row.status === 'pending'" link type="primary" size="small" @click="updateStatus(row, 'processing')">处理</el-button>
          <el-button v-if="row.status === 'processing'" link type="success" size="small" @click="updateStatus(row, 'resolved')">完成</el-button>
          <el-button v-if="canDelete(adminStore.role)" link type="danger" size="small" @click="handleDeleteReport(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 服务端分页器：total 来自后端，翻页/换筛选都重新查询 -->
    <div class="pagination-bar">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        :current-page="page"
        :page-size="size"
        :page-sizes="[10, 20, 50]"
        @current-change="handlePageChange"
        @size-change="(s: number) => { size = s; page = 1; loadReports() }"
      />
    </div>
  </el-card>

  <!-- 详情弹窗 -->
  <el-dialog v-model="detailVisible" title="农情详情" width="600px">
    <div v-if="selectedReport" class="detail-content">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="标题" :span="2">{{ selectedReport.title }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ typeLabels[selectedReport.reportType] }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabels[selectedReport.status] }}</el-descriptions-item>
        <el-descriptions-item label="上报人">{{ selectedReport.nickname || '--' }}</el-descriptions-item>
        <el-descriptions-item label="村镇">{{ villageName(selectedReport.villageCode) }}</el-descriptions-item>
        <el-descriptions-item label="作物">{{ selectedReport.cropName || '--' }}</el-descriptions-item>
        <el-descriptions-item label="生长阶段">{{ selectedReport.growthStage || '--' }}</el-descriptions-item>
        <el-descriptions-item label="紧急程度">{{ urgencyLabels[selectedReport.urgency] }}</el-descriptions-item>
        <el-descriptions-item label="上报时间">{{ formatTime(selectedReport.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ selectedReport.description }}</el-descriptions-item>
      </el-descriptions>

      <!-- AI 智能受理结果：只作参考，采纳与否由专家决定 -->
      <div v-if="selectedReport.aiParsed || selectedReport.aiSuggestedAction" class="ai-box">
        <div class="ai-box-head">
          <el-tag type="success" size="small" effect="dark">AI 智能受理</el-tag>
          <span class="ai-box-hint">该工单由模型从农户原话整理而成，以下为模型给出的初步建议，仅供参考</span>
        </div>
        <div v-if="selectedReport.aiSuggestedAction" class="ai-box-body">{{ selectedReport.aiSuggestedAction }}</div>
      </div>

      <div class="ledger-bar">
        <el-button type="primary" plain size="small" @click="openLedger(selectedReport)">登记帮扶台账</el-button>
        <span class="ledger-hint">台账是帮扶结案记录，计入帮扶成效看板；上报标记「已解决」时系统也会自动建档。</span>
      </div>

      <div class="response-section">
        <div class="response-section-head">
          <h4>处理沟通 ({{ responses.length }})</h4>
          <el-button link type="primary" size="small" @click="openAddResponse(selectedReport)">新增沟通</el-button>
        </div>
        <el-timeline v-if="responses.length > 0">
          <el-timeline-item v-for="r in responses" :key="r.id" :timestamp="formatTime(r.createdAt)" placement="top">
            <el-card shadow="never" class="response-card">
              <div class="response-header">
                <span class="response-name">{{ r.responderName || '处理人' }}</span>
                <el-tag size="small" :type="r.responseType === 'guidance' ? 'success' : r.responseType === 'dispatch' ? 'primary' : 'info'">
                  {{ responseTypeLabels[r.responseType] || r.responseType }}
                </el-tag>
                <el-button link type="danger" size="small" style="margin-left: auto" @click="handleDeleteResponse(r)">删除</el-button>
              </div>
              <div class="response-content">{{ r.content }}</div>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <div v-else class="empty-response">暂无处理沟通</div>
      </div>
    </div>
  </el-dialog>

  <!-- 新增处理沟通弹窗 -->
  <el-dialog v-model="responseVisible" title="新增处理沟通" width="500px">
    <el-form :model="responseForm" label-width="90px">
      <el-form-item label="沟通方式">
        <el-select v-model="responseForm.responseType" style="width: 100%">
          <el-option label="技术指导" value="guidance" />
          <el-option label="派人处理" value="dispatch" />
          <el-option label="回复" value="reply" />
          <el-option label="关闭" value="close" />
        </el-select>
      </el-form-item>
      <el-form-item label="处理人">
        <el-input v-model="responseForm.responderName" placeholder="留空默认显示为「管理员」" maxlength="32" />
      </el-form-item>
      <el-form-item label="沟通内容">
        <el-input v-model="responseForm.content" type="textarea" :rows="4" maxlength="1000" show-word-limit
                  placeholder="请输入技术指导或处理意见" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="responseVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitResponse">提交</el-button>
    </template>
  </el-dialog>

  <!-- 登记帮扶台账弹窗 -->
  <el-dialog v-model="ledgerVisible" title="登记帮扶台账" width="520px">
    <el-alert
      type="warning" :closable="false" show-icon class="ledger-alert"
      title="台账为帮扶结案记录，会计入帮扶成效看板，同一上报重复登记会被拦截；处理过程中的沟通请记录在「处理沟通」中。"
    />
    <el-form :model="ledgerForm" label-width="80px">
      <el-form-item label="帮扶类型">
        <el-select v-model="ledgerForm.assistanceType" style="width: 100%">
          <el-option v-for="t in assistanceTypes" :key="t" :label="t" :value="t" />
        </el-select>
      </el-form-item>
      <el-form-item label="帮扶内容">
        <el-input v-model="ledgerForm.description" type="textarea" :rows="4" maxlength="250" show-word-limit />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="ledgerForm.status" style="width: 100%">
          <el-option v-for="s in assistanceStatus" :key="s" :label="s" :value="s" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="ledgerVisible = false">取消</el-button>
      <el-button type="primary" :loading="ledgerSubmitting" @click="submitLedger">确认登记</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-count {
  font-size: 13px;
  color: #6B7280;
  margin-left: auto;
}

.text-muted {
  font-size: 12px;
  color: #6B7280;
}

.detail-content {
  padding: 8px 0;
}

/* AI 受理结果用绿色系，与「台账」的橙色区分开：一个是模型建议，一个是人确认过的结论 */
.ai-box {
  margin-top: 14px;
  padding: 10px 12px;
  background: #f0f9f4;
  border: 1px solid #c2e3d0;
  border-radius: 6px;
}
.ai-box-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.ai-box-hint {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}
.ai-box-body {
  font-size: 13px;
  color: #1f2937;
  line-height: 1.8;
  white-space: pre-wrap;
}

.ledger-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding: 10px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
}

.ledger-hint {
  font-size: 12px;
  color: #8a6d3b;
  line-height: 1.5;
}

.ledger-alert {
  margin-bottom: 14px;
}

.response-section {
  margin-top: 16px;
}

.response-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 12px;
}

.response-section-head h4 {
  margin: 0;
  color: #333;
}

.response-card {
  margin-bottom: 4px;
}

.response-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.response-name {
  font-weight: 600;
  color: #333;
  font-size: 14px;
}

.response-content {
  font-size: 14px;
  color: #555;
  line-height: 1.6;
}

.empty-response {
  text-align: center;
  padding: 20px 0;
  color: #999;
  font-size: 14px;
}
</style>

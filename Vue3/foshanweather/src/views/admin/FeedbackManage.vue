﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getAdminFeedbacksPaged, updateFeedback } from '@/api/admin'
import { useNoticeStore } from '@/stores/notice'
import { ElMessage } from 'element-plus'
import type { Feedback } from '@/types/weather'

const route = useRoute()

/** 反馈列表数据（当前页） */
const feedbacks = ref<Feedback[]>([])
/** 列表加载状态 */
const loading = ref(false)
/** 回复弹窗可见性 */
const replyDialogVisible = ref(false)
/** 当前正在回复的反馈对象 */
const currentFeedback = ref<Feedback | null>(null)
/** 回复文本内容 */
const replyText = ref('')
/** 全局待办角标 store（处理反馈后即时刷新侧栏角标） */
const noticeStore = useNoticeStore()

// ========== 分页 + 状态筛选 ==========
// 筛选与翻页由后端处理；el-pagination 从 1 起始，后端 page 从 0 起始。
const page = ref(1)
const size = ref(20)
const total = ref(0)
const statusFilter = ref('')

// 组件挂载时加载数据；支持概览页 ?status=pending 直达预选
onMounted(() => {
  const s = route.query.status
  if (s === 'pending' || s === 'replied' || s === 'resolved') {
    statusFilter.value = s
  }
  loadData()
})

/** 加载反馈列表数据 */
async function loadData() {
  loading.value = true
  try {
    const paged = await getAdminFeedbacksPaged({
      page: page.value - 1,
      size: size.value,
      status: statusFilter.value || undefined,
    })
    feedbacks.value = paged.items
    total.value = paged.total
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '加载失败') }
  finally { loading.value = false }
}

// 改筛选回第 1 页，避免停在超出范围的页码
watch(statusFilter, () => {
  page.value = 1
  loadData()
})

function handlePageChange(p: number) {
  page.value = p
  loadData()
}

/** 打开回复弹窗，填充已有回复内容 */
function openReply(row: Feedback) {
  currentFeedback.value = row
  replyText.value = row.reply || ''
  replyDialogVisible.value = true
}

/** 提交回复，更新反馈状态为已回复 */
async function submitReply() {
  if (!currentFeedback.value) return
  try {
    await updateFeedback(currentFeedback.value.id, { status: 'replied', reply: replyText.value })
    ElMessage.success('回复成功')
    replyDialogVisible.value = false
    loadData()
    noticeStore.fetchCounts()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '回复失败') }
}

/** 将反馈标记为已解决 */
async function markResolved(id: number) {
  try {
    await updateFeedback(id, { status: 'resolved' })
    ElMessage.success('已标记为已处理')
    loadData()
    noticeStore.fetchCounts()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '操作失败') }
}

/** 根据反馈状态返回标签类型，用于颜色区分 */
function statusType(s: string) {
  if (s === 'pending') return 'warning'
  if (s === 'replied') return 'primary'
  return 'success'
}

/** 将状态英文键映射为中文标签 */
function statusLabel(s: string) {
  const map: Record<string, string> = { pending: '待处理', replied: '已回复', resolved: '已解决' }
  return map[s] || s
}
</script>

<template>
  <el-card shadow="hover">
    <template #header>用户反馈管理</template>

    <div class="filter-bar">
      <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width: 130px">
        <el-option label="待处理" value="pending" />
        <el-option label="已回复" value="replied" />
        <el-option label="已解决" value="resolved" />
      </el-select>
      <span class="filter-count">共 {{ total }} 条反馈</span>
    </div>

    <!-- 反馈列表表格 -->
    <el-table :data="feedbacks" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="feedbackType" label="类型" width="100" />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reply" label="回复" show-overflow-tooltip />
      <el-table-column prop="createdAt" label="提交时间" width="160" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openReply(row)">回复</el-button>
          <!-- 未解决的反馈才显示"标记解决"按钮 -->
          <el-button v-if="row.status !== 'resolved'" type="success" text size="small" @click="markResolved(row.id)">标记解决</el-button>
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

  <!-- 回复反馈弹窗 -->
  <el-dialog v-model="replyDialogVisible" title="回复反馈" width="500px">
    <!-- 显示用户原始反馈内容 -->
    <div v-if="currentFeedback" style="margin-bottom: 16px">
      <p><strong>用户反馈:</strong> {{ currentFeedback.content }}</p>
      <p v-if="currentFeedback.imageUrls"><strong>附图:</strong> {{ currentFeedback.imageUrls }}</p>
    </div>
    <el-input v-model="replyText" type="textarea" :rows="4" placeholder="输入回复内容" />
    <template #footer>
      <el-button @click="replyDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="submitReply">提交回复</el-button>
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
</style>
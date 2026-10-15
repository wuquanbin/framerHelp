<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox, ElEmpty } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import { useHumanChatStore } from '@/stores/humanChat'
import {
  listHumanChatSessions,
  listHumanChatMessages,
  closeHumanChatSession,
  markHumanChatRead,
} from '@/api/admin'
import { adminChatWs } from '@/utils/adminChatWs'
import type { HumanChatSession, HumanChatMessage } from '@/types/weather'

const adminStore = useAdminStore()
const humanChatStore = useHumanChatStore()

const loadingSessions = ref(false)
const sessions = ref<HumanChatSession[]>([])
const activeSessionId = ref<number | null>(null)
const messages = ref<HumanChatMessage[]>([])
const inputText = ref('')
const statusFilter = ref('') // ''=全部 / WAITING / ACTIVE / CLOSED
const online = ref(true) // 管理员自己是否"在线"
const onlineAdminMap = ref<Record<string, string>>({})
const totalUnread = ref(0)
const messagesEndRef = ref<HTMLElement | null>(null)
const chatContainer = ref<HTMLElement | null>(null)
const aiContextModal = ref(false)
const currentAiContext = ref('')

const activeSession = computed<HumanChatSession | undefined>(
  () => sessions.value.find(s => s.id === activeSessionId.value),
)

const loadingMessages = ref(false)

const statusLabel = (s: HumanChatSession) => {
  switch (s.status) {
    case 'WAITING': return { text: '待接单', color: '#e6a23c', bg: 'rgba(230,162,60,0.1)' }
    case 'ACTIVE': return { text: '进行中', color: '#409eff', bg: 'rgba(64,158,255,0.1)' }
    case 'CLOSED': return { text: '已关闭', color: '#909399', bg: 'rgba(144,147,153,0.1)' }
    default: return { text: s.status, color: '#606266', bg: 'transparent' }
  }
}

// ========== 同一分钟内消息只显示一次时间（不含秒） ==========
function minuteKey(t?: string) {
  return (t || '').replace('T', ' ').slice(0, 16)
}
function applyShowTime<T extends HumanChatMessage>(m: T, prev?: HumanChatMessage | null): T {
  const key = minuteKey(m.createdAt)
  return { ...m, showTime: !!key && key !== minuteKey(prev?.createdAt) } as T
}
// 会话卡片时间：优先最后消息时间，格式 yyyy-MM-dd HH:mm
function fmtSessionTime(s: HumanChatSession) {
  const t = s.lastMessageTime || s.createdAt || ''
  return t.replace('T', ' ').slice(0, 16)
}

// ========== 加载会话列表 ==========
async function loadSessions() {
  loadingSessions.value = true
  try {
    const r = await listHumanChatSessions(statusFilter.value || undefined)
    sessions.value = r.list
    onlineAdminMap.value = r.onlineAdmins
    totalUnread.value = r.totalUnread
    // 如果当前已选中的会话列表里没有了，清空选中
    if (activeSessionId.value && !sessions.value.find(s => s.id === activeSessionId.value)) {
      activeSessionId.value = null
      messages.value = []
    }
    // 若没选中会话，优先选中一个有未读的或 WAITING 的
    if (!activeSessionId.value && sessions.value.length) {
      const target = sessions.value.find(s => s.adminUnread > 0)
        ?? sessions.value.find(s => s.status === 'WAITING')
        ?? sessions.value[0]
      if (target) await selectSession(target)
    }
  } catch (e: any) {
    ElMessage.error(e.message || '加载会话列表失败')
  } finally {
    loadingSessions.value = false
    humanChatStore.fetchSummary() // 同步侧栏徽标（待接单数/未读数）
  }
}

async function selectSession(s: HumanChatSession) {
  if (activeSessionId.value === s.id) return
  activeSessionId.value = s.id
  loadingMessages.value = true
  try {
    if (s.status !== 'CLOSED' && s.adminUnread > 0) {
      await markHumanChatRead(s.id)
      humanChatStore.fetchSummary() // 清未读后同步侧栏徽标
    }
    messages.value = (await listHumanChatMessages(s.id)).map((m, i, list) => applyShowTime(m, list[i - 1]))
  } catch (e: any) {
    ElMessage.error(e.message || '加载历史消息失败')
  } finally {
    loadingMessages.value = false
    await nextTick()
    scrollToBottom()
  }
}

function scrollToBottom() {
  requestAnimationFrame(() => {
    messagesEndRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  })
}

function viewAiContext(s: HumanChatSession) {
  currentAiContext.value = s.aiContextSnapshot || '（无 AI 上下文）'
  aiContextModal.value = true
}

watch(statusFilter, () => loadSessions())

// ========== 聊天发送 / 接单 / 关闭 ==========
function sendMsg() {
  const text = inputText.value.trim()
  if (!text || !activeSessionId.value) return
  const s = activeSession.value
  if (!s) return
  if (s.status === 'CLOSED') {
    ElMessage.warning('该会话已关闭')
    return
  }
  if (s.status === 'WAITING') {
    // 先发 ADMIN_ACCEPT 接单
    adminChatWs.send('ADMIN_ACCEPT', { sessionId: activeSessionId.value })
  }
  adminChatWs.send('CHAT', { sessionId: activeSessionId.value, content: text })
  inputText.value = ''
}

function acceptSession(s: HumanChatSession) {
  if (s.status !== 'WAITING') return
  adminChatWs.send('ADMIN_ACCEPT', { sessionId: s.id })
  selectSession(s)
  ElMessage.success(`已接待：${s.farmerNickname || '农户'}`)
}

async function closeCurrent() {
  const s = activeSession.value
  if (!s) return
  try {
    await ElMessageBox.confirm(`确定关闭与「${s.farmerNickname || '农户'}」的会话？`, '提示', { type: 'warning' })
    adminChatWs.send('CLOSE', { sessionId: s.id })
  } catch (_) { /* cancel */ }
}

// ========== 在线状态切换 ==========
function toggleOnline(val: boolean) {
  online.value = val
  adminChatWs.send('ADMIN_STATUS', { online: val })
  if (val) ElMessage.success('已上线，等待新会话')
  else ElMessage.info('已离线，农民将暂时看不到您在线')
}

// ========== WebSocket 事件 ==========
const __unsubs: Array<() => void> = []

function bindWsHandlers() {
  __unsubs.push(
    adminChatWs.on('__OPEN__', () => {
      // 连接成功后立即发送在线状态
      adminChatWs.send('ADMIN_STATUS', { online: online.value })
      loadSessions()
    }),
  )
  __unsubs.push(
    adminChatWs.on('__CLOSE__', () => {
      // 自动重连由 service 处理
    }),
  )
  __unsubs.push(
    adminChatWs.on('NEW_SESSION_NOTIFY', async () => {
      await loadSessions()
      // 有新订单，轻微提示
      if (document.hidden) {
        ElMessage.info('有新的农民请求转人工！')
      }
    }),
  )
  __unsubs.push(
    adminChatWs.on('SESSION_ACCEPTED_BROADCAST', async (m) => {
      // 别人接单了，刷新列表看状态变化
      const b = m.body as any
      // 恰好是我当前选中的会话
      if (activeSessionId.value && String(activeSessionId.value) === String(b.sessionId)) {
        await loadSessions()
      } else {
        await loadSessions()
      }
    }),
  )
  __unsubs.push(
    adminChatWs.on('SESSION_ACCEPTED', async (m) => {
      const b = m.body as any
      if (b.session?.id && b.session.id === activeSessionId.value) {
        // 本会话已被确认开始
        await loadSessions()
      } else {
        await loadSessions()
      }
    }),
  )
  __unsubs.push(
    adminChatWs.on('NEW_CHAT', (m) => {
      const b = m.body as { sessionId: number; message: HumanChatMessage }
      if (!b?.message) return
      // 如果是当前会话，追加到 messages；否则只刷新会话列表（未读变化）
      if (activeSessionId.value && activeSessionId.value === b.sessionId) {
        if (!messages.value.find(x => x.id === b.message.id)) {
          messages.value.push(applyShowTime(b.message, messages.value[messages.value.length - 1]))
          nextTick(() => scrollToBottom())
        }
        // 来自农民，本地清已读（前端即时状态）
        if (b.message.senderType === 'FARMER') {
          markHumanChatRead(b.sessionId).catch(() => { })
        }
      }
      // 刷新左侧 lastMessage / 未读数
      loadSessions()
    }),
  )
  __unsubs.push(
    adminChatWs.on('SESSION_UNREAD_UPDATE', async () => {
      await loadSessions()
    }),
  )
  __unsubs.push(
    adminChatWs.on('SESSION_CLOSED', async (m) => {
      const b = m.body as any
      if (activeSessionId.value && String(activeSessionId.value) === String(b.sessionId)) {
        ElMessage.info('会话已关闭')
      }
      await loadSessions()
    }),
  )
  __unsubs.push(
    adminChatWs.on('ERROR', (m) => {
      const msg = (m.body as any)?.message || '服务端返回错误'
      ElMessage.warning(msg)
    }),
  )
}

// ========== 生命周期 ==========
onMounted(async () => {
  bindWsHandlers()
  adminChatWs.connect()
  await loadSessions()
  // 若当前选中的会话有未读，先把自己设为已读（后台已在 selectSession 中做）
  document.title = `人工客服工作台 - 乡村农业气象帮扶系统`
})

onUnmounted(() => {
  // 只解绑事件回调，不断开 WS 连接：
  // 管理员切到其他后台页面时保持在线，农民请求仍能收到广播（重进本页自动重新绑定）
  __unsubs.forEach(fn => fn())
})
</script>

<template>
  <div class="workbench">
    <!-- 顶栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <h2 class="page-title">人工客服工作台</h2>
        <div class="online-wrap">
          <div :class="['online-dot', online ? 'dot-on' : 'dot-off']"></div>
          <span class="online-text">{{ online ? '在线接单中' : '离线（不接收新会话）' }}</span>
          <el-switch v-model="online" @change="toggleOnline" style="margin-left: 8px" />
        </div>
      </div>
      <div class="toolbar-right">
        <el-radio-group v-model="statusFilter" size="default" style="margin-right: 12px">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="WAITING">待接单</el-radio-button>
          <el-radio-button value="ACTIVE">进行中</el-radio-button>
          <el-radio-button value="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
        <el-button type="primary" @click="loadSessions">刷新</el-button>
      </div>
    </div>

    <!-- 主面板 -->
    <div class="main">
      <!-- 左侧会话列表 -->
      <div class="session-list-panel">
        <div class="session-header">
          <span>会话列表</span>
          <el-badge v-if="totalUnread > 0" :value="totalUnread" class="total-badge" type="danger" />
        </div>
        <el-scrollbar class="session-scroll">
          <el-empty v-if="!sessions.length && !loadingSessions" description="暂无会话" />
          <div v-for="s in sessions" :key="s.id" :class="['session-item', { active: s.id === activeSessionId }]"
            @click="selectSession(s)">
            <div class="s-row1">
              <span class="s-name">{{ s.farmerNickname || '匿名农户' }}</span>
              <el-tag size="small" :color="statusLabel(s).bg" effect="plain"
                :style="{ color: statusLabel(s).color, borderColor: statusLabel(s).color, padding: '0 8px' }">{{
                statusLabel(s).text }}</el-tag>
            </div>
            <div class="s-row2">
              <span class="s-village">📍 {{ s.villageName || s.villageCode }}</span>
              <span v-if="s.adminRealName" class="s-admin">👨‍🌾 {{ s.adminRealName }}</span>
            </div>
            <div class="s-row3">
              <span class="s-preview">
                {{ s.lastMessage || '（暂无消息）' }}
              </span>
              <el-badge v-if="s.adminUnread > 0" :value="s.adminUnread" type="danger" :max="99" class="s-badge" />
            </div>
            <div class="s-row4">
              <span class="s-time">{{ fmtSessionTime(s) }}</span>
              <div class="s-actions" @click.stop>
                <el-button v-if="s.status === 'WAITING'" type="primary" size="small" link
                  @click="acceptSession(s)">接单</el-button>
                <el-button v-if="s.aiContextSnapshot" size="small" link @click="viewAiContext(s)">AI上下文</el-button>
              </div>
            </div>
          </div>
        </el-scrollbar>
      </div>

      <!-- 右侧聊天 -->
      <div class="chat-panel">
        <template v-if="activeSession">
          <div class="chat-header">
            <div class="chat-header-left">
              <div class="avatar">🌾</div>
              <div>
                <div class="c-name">{{ activeSession.farmerNickname || '匿名农户' }}
                  <el-tag size="small" style="margin-left: 6px">
                    {{ activeSession.villageName || activeSession.villageCode }}
                  </el-tag>
                </div>
                <div class="c-sub">
                  会话状态：
                  <span :style="{ color: statusLabel(activeSession).color }">{{ statusLabel(activeSession).text
                    }}</span>
                  <span v-if="activeSession.startedAt"> ｜ 开始于 {{ activeSession.startedAt.replace('T', ' ').slice(0, 16)
                    }}</span>
                </div>
              </div>
            </div>
            <div class="chat-header-right">
              <el-button v-if="activeSession.aiContextSnapshot" link
                @click="viewAiContext(activeSession)">查看AI上下文</el-button>
              <el-button v-if="activeSession.status !== 'CLOSED'" type="danger" link
                @click="closeCurrent">关闭会话</el-button>
            </div>
          </div>

          <el-scrollbar ref="chatContainer" class="chat-body">
            <div v-if="loadingMessages" class="chat-loading">
              <el-icon class="is-loading">
                <component :is="'Loading'" />
              </el-icon>
              <span>加载消息...</span>
            </div>
            <template v-else>
              <el-empty v-if="messages.length === 0" description="暂未开始对话，开始第一条消息吧" />
              <div v-for="m in messages" :key="m.id"
                :class="['msg-row', m.senderType === 'ADMIN' ? 'row-me' : 'row-other']">
                <div v-if="m.senderType === 'FARMER'" class="msg-avatar">🌾</div>
                <div class="msg-col">
                  <div class="msg-meta">
                    <span>{{ m.senderName }}</span>
                    <span v-if="m.showTime" class="msg-time">{{ m.createdAt.replace('T', ' ').slice(0, 16) }}</span>
                  </div>
                  <div :class="['msg-bubble', m.senderType === 'ADMIN' ? 'me' : 'other']">
                    {{ m.content }}
                  </div>
                </div>
                <div v-if="m.senderType === 'ADMIN'" class="msg-avatar me">👨‍🌾</div>
              </div>
            </template>
            <div ref="messagesEndRef" />
          </el-scrollbar>

          <div v-if="activeSession.status === 'CLOSED'" class="closed-hint">该会话已关闭</div>
          <div v-else class="chat-input-wrap">
          <div class="input-row">
            <el-input
              v-model="inputText"
              type="textarea"
              :rows="2"
              resize="none"
              placeholder="回答农民的种植、施肥、排水等农事问题..."
              @keydown="(e: KeyboardEvent) => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMsg() }
              }"
            />
            <el-button
              type="primary"
              class="send-btn"
              :disabled="!inputText.trim()"
              @click="sendMsg"
            >发送</el-button>
          </div>
        </div>
        </template>
        <div v-else class="chat-empty">
          <component :is="ElEmpty" description="请从左侧选择一个会话" />
        </div>
      </div>
    </div>

    <!-- AI 上下文 Modal -->
    <el-dialog v-model="aiContextModal" title="AI 对话上下文快照（农民转人工前的提问）" width="60%" top="8vh">
      <pre
        style="white-space: pre-wrap; background: #fafafa; padding: 16px; border-radius: 8px; max-height: 60vh; overflow: auto;">
    {{ currentAiContext }}</pre>
    </el-dialog>
  </div>
</template>

<style scoped>
.workbench {
  height: var(--ad-view-h, calc(100vh - 120px));
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  padding: 12px 20px;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 20px;
}

.page-title {
  font-size: 18px;
  margin: 0;
  font-weight: 600;
  color: #2d6a4f;
}

.online-wrap {
  display: flex;
  align-items: center;
}

.online-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.1);
}

.dot-on {
  background: #67c23a;
  box-shadow: 0 0 0 3px rgba(103, 194, 58, 0.2);
}

.dot-off {
  background: #c0c4cc;
}

.online-text {
  color: #606266;
  font-size: 14px;
}

.total-badge {
  margin-left: 8px;
}

.main {
  flex: 1;
  display: flex;
  gap: 16px;
  min-height: 0;
}

/* 左侧会话列表 */
.session-list-panel {
  width: 340px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.session-header {
  padding: 14px 16px;
  border-bottom: 1px solid #ebeef5;
  font-weight: 600;
  color: #303133;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.session-scroll {
  flex: 1;
  overflow: auto;
}

.session-item {
  padding: 12px 16px;
  border-bottom: 1px solid #f2f6fc;
  cursor: pointer;
  transition: background 0.15s;
}

.session-item:hover {
  background: #f7faff;
}

.session-item.active {
  background: #ecf6f1;
  border-left: 4px solid #52b788;
}

.s-row1 {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.s-name {
  font-weight: 600;
  color: #303133;
  font-size: 15px;
}

.s-row2 {
  display: flex;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 12px;
  color: #6B7280;
}

.s-row3 {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.s-preview {
  color: #606266;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.s-badge {
  flex-shrink: 0;
}

.s-row4 {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.s-time {
  color: #6B7280;
  font-size: 12px;
}

.s-actions {
  display: flex;
  gap: 8px;
}

/* 右侧聊天 */
.chat-panel {
  flex: 1;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6B7280;
}

.chat-header {
  padding: 14px 20px;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: linear-gradient(135deg, #b7e4c7, #52b788);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: #fff;
}

.c-name {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.c-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #6B7280;
}

.chat-header-right {
  display: flex;
  gap: 8px;
}

.chat-body {
  flex: 1;
  background: #f5f7fa;
  padding: 20px 28px;
}

.chat-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #6B7280;
  justify-content: center;
  padding: 40px;
}

.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}

.row-other {
  justify-content: flex-start;
}

.row-me {
  justify-content: flex-end;
}

.row-me .msg-col {
  align-items: flex-end;
}

.row-me .msg-meta {
  justify-content: flex-end;
}

.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #b7e4c7, #52b788);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  color: #fff;
  flex-shrink: 0;
}

.msg-avatar.me {
  background: linear-gradient(135deg, #a8d8ff, #409eff);
}

.msg-col {
  max-width: 65%;
  display: flex;
  flex-direction: column;
}

.msg-meta {
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #6B7280;
  margin-bottom: 4px;
}

.msg-bubble {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-bubble.other {
  background: #fff;
  color: #303133;
  border-top-left-radius: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.msg-bubble.me {
  background: #52b788;
  color: #fff;
  border-top-right-radius: 4px;
}

.closed-hint {
  padding: 12px 20px;
  color: #6B7280;
  text-align: center;
  background: #f5f7fa;
  border-top: 1px solid #ebeef5;
}

.chat-input-wrap {
  padding: 14px 20px 16px;
  border-top: 1px solid #ebeef5;
}

/* 输入框与发送按钮同一行：输入框占满剩余宽度（变短），按钮在右侧 */
.input-row {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.input-row :deep(.el-input) {
  flex: 1;
}

.send-btn {
  height: 54px;
  padding: 0 24px;
  font-size: 14px;
  flex-shrink: 0;
}
</style>

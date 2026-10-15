<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { getAdminUsersPaged, getAdminUser, updateUserStatus, deleteUser, getAdminAdmins, createAdmin, updateAdmin, deleteAdmin, updateAdminStatus } from '@/api/admin'
import { useAdminStore } from '@/stores/admin'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { User, AdminUser } from '@/types/weather'
import { resolveFileUrl } from '@/utils/format'
import { roleLabel, roleTagType, normalizeRole, canDelete, isSuperAdmin } from '@/utils/roles'

const adminStore = useAdminStore()

const activeTab = ref('users')
const users = ref<User[]>([])
const admins = ref<AdminUser[]>([])
const loading = ref(false)

// ========== 农户搜索筛选 ==========
// 关键词/状态/村镇条件由后端查询，保证数据量大时翻页后搜索结果仍然完整。
const searchKeyword = ref('')
const statusFilter = ref('')
const villageFilter = ref('')

// ========== 农户列表服务端分页 ==========
// el-pagination 的 current-page 是 1 起始，后端 page 是 0 起始，请求时减 1。
const page = ref(1)
const size = ref(20)
const total = ref(0)

// ========== 农户详情 ==========
const detailVisible = ref(false)
const detailUser = ref<User | null>(null)
const detailLoading = ref(false)

async function openDetail(id: number) {
  detailLoading.value = true
  detailVisible.value = true
  try {
    detailUser.value = await getAdminUser(id)
  } catch {
    ElMessage.error('获取用户详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function toggleUserStatus(user: User) {
  const newStatus = user.status === 1 ? 0 : 1
  const action = newStatus === 0 ? '禁用' : '启用'
  try { await ElMessageBox.confirm(`确定${action}用户「${user.nickname || user.openid}」？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await updateUserStatus(user.id, newStatus)
    ElMessage.success(`已${action}`)
    loadData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

/**
 * 删除农户账号。
 * 双重确认 + 明确告知连带删除范围 —— 销户不可恢复，
 * 「确定删除吗」这种一句话提示在误点时代价太大。
 */
async function handleDeleteUser(user: User) {
  const name = user.nickname || user.openid
  try {
    await ElMessageBox.confirm(
      `将删除农户「${name}」及其帮扶台账、反馈、我的作物、农事任务记录。农情上报作为村镇公共台账保留。`,
      '删除农户账号',
      { type: 'warning', confirmButtonText: '我已了解，继续', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await ElMessageBox.confirm(`此操作不可恢复，确认删除「${name}」？`, '再次确认', {
      type: 'error',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await deleteUser(user.id)
    ElMessage.success('已删除该农户')
    loadData()
  } catch (e: any) {
    // 403 多半是「当前角色不是超级管理员」，说清楚而不是弹一句「删除失败」
    ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}

// ========== 管理员管理 ==========
const adminSearchKeyword = ref('')
const adminDialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<any>({ username: '', password: '', realName: '', phone: '', email: '', role: 'EXPERT', villageCode: '', status: 1 })

const filteredAdmins = computed(() => {
  if (!adminSearchKeyword.value) return admins.value
  const kw = adminSearchKeyword.value.toLowerCase()
  return admins.value.filter(a =>
    (a.username || '').toLowerCase().includes(kw) ||
    (a.realName || '').toLowerCase().includes(kw) ||
    (a.phone || '').includes(kw)
  )
})

function openCreateAdmin() {
  isEdit.value = false
  form.value = { username: '', password: '', realName: '', phone: '', email: '', role: 'EXPERT', villageCode: '', status: 1 }
  adminDialogVisible.value = true
}

function openEditAdmin(row: AdminUser) {
  isEdit.value = true
  // 已下线角色（DATA_ADMIN / CONTENT_ADMIN）打开编辑时归一成 EXPERT，
  // 避免下拉框出现选项列表中不存在的值
  form.value = { ...row, password: '', role: normalizeRole(row.role), villageCode: row.villageCode ?? '' }
  adminDialogVisible.value = true
}

async function saveAdmin() {
  try {
    if (isEdit.value) await updateAdmin(form.value.id, form.value)
    else await createAdmin(form.value)
    ElMessage.success(isEdit.value ? '更新成功' : '创建成功')
    adminDialogVisible.value = false
    loadData()
  } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '操作失败') }
}

async function toggleAdminStatus(row: AdminUser) {
  const newStatus = row.status === 1 ? 0 : 1
  const action = newStatus === 0 ? '禁用' : '启用'
  try { await ElMessageBox.confirm(`确定${action}管理员「${row.realName || row.username}」？`, '提示', { type: 'warning' }) } catch { return }
  try {
    await updateAdminStatus(row.id, newStatus)
    ElMessage.success(`已${action}`)
    loadData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

async function handleDeleteAdmin(row: AdminUser) {
  if (row.username === adminStore.username) {
    ElMessage.warning('不能删除当前登录的管理员')
    return
  }
  try { await ElMessageBox.confirm(`确定删除管理员「${row.realName || row.username}」？此操作不可恢复。`, '提示', { type: 'warning' }) } catch { return }
  try { await deleteAdmin(row.id); ElMessage.success('已删除'); loadData() } catch (e: any) { ElMessage.error(e?.response?.data?.message || e?.message || '删除失败') }
}

// 角色映射与权限判断统一走 @/utils/roles（与后端 AdminRole 一一对应），
// 避免多处各维护一份映射导致不一致
const roleOptions = [
  { label: '超级管理员', value: 'SUPER_ADMIN' },
  { label: '专家', value: 'EXPERT' },
  { label: '村镇管理员', value: 'VILLAGE_ADMIN' },
]

// ========== 通用 ==========
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

onMounted(loadData)

// 改筛选条件时回到第 1 页（否则会停在超出范围的页码上，看到空表格）
watch([searchKeyword, statusFilter, villageFilter], () => {
  page.value = 1
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    if (activeTab.value === 'users') {
      const paged = await getAdminUsersPaged({
        page: page.value - 1, // 前端 1 起始 → 后端 0 起始
        size: size.value,
        keyword: searchKeyword.value || undefined,
        status: statusFilter.value === '' ? undefined : Number(statusFilter.value),
        villageCode: villageFilter.value || undefined,
      })
      users.value = paged.items
      total.value = paged.total
    } else {
      // 管理员数量是常量级的（个位数），没必要分页，保持一次取全
      admins.value = await getAdminAdmins()
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function handlePageChange(p: number) {
  page.value = p
  loadData()
}

/** 切 Tab 时把农户列表重置到第 1 页，避免带着上一页页码去查另一批数据 */
function onTabChange() {
  page.value = 1
  loadData()
}
</script>

<template>
  <el-card shadow="hover">
    <template #header>人员管理</template>
    <el-tabs v-model="activeTab" @tab-change="onTabChange">

      <!-- 农户列表 -->
      <el-tab-pane label="农户列表" name="users">
        <div class="filter-bar">
          <el-input v-model="searchKeyword" placeholder="搜索昵称/ID/手机号" clearable style="width: 220px" prefix-icon="Search" />
          <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width: 120px">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
          <el-select v-model="villageFilter" placeholder="村镇筛选" clearable style="width: 120px">
            <el-option v-for="d in villageOptions" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
          <span class="filter-count">共 {{ total }} 位农户</span>
        </div>

        <el-table :data="users" v-loading="loading" stripe border>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="农户" min-width="180">
            <template #default="{ row }">
              <div class="user-cell">
                <el-avatar :size="36" :src="resolveFileUrl(row.avatarUrl) || undefined" class="user-avatar">
                  {{ (row.nickname || '?')[0] }}
                </el-avatar>
                <div class="user-info">
                  <div class="user-name">{{ row.nickname || '未设置昵称' }}</div>
                  <div class="user-sub">ID: {{ row.id }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="所属村镇" width="100">
            <template #default="{ row }">{{ villageName(row.villageCode) }}</template>
          </el-table-column>
          <el-table-column prop="phone" label="手机" width="120">
            <template #default="{ row }">{{ row.phone || '--' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '正常' : '禁用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后登录" width="150">
            <template #default="{ row }">{{ formatTime(row.lastLoginTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row.id)">详情</el-button>
              <el-button link :type="row.status === 1 ? 'danger' : 'success'" size="small" @click="toggleUserStatus(row)">
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <!-- 删除农户仅超级管理员可用：销户不可恢复，比禁用重得多 -->
              <el-button v-if="isSuperAdmin(adminStore.role)" link type="danger" size="small" @click="handleDeleteUser(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 服务端分页器：total 来自后端，翻页/改筛选都重新查询 -->
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
      </el-tab-pane>

      <!-- 管理员 -->
      <el-tab-pane label="管理员" name="admins">
        <div class="filter-bar">
          <el-input v-model="adminSearchKeyword" placeholder="搜索用户名/姓名/手机号" clearable style="width: 220px" prefix-icon="Search" />
          <el-button type="primary" @click="openCreateAdmin">新增管理员</el-button>
          <span class="filter-count">共 {{ filteredAdmins.length }} 位管理员</span>
        </div>

        <el-table :data="filteredAdmins" v-loading="loading" stripe border>
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="username" label="账号" width="120" />
          <el-table-column prop="realName" label="姓名" width="100">
            <template #default="{ row }">{{ row.realName || '--' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="120">
            <template #default="{ row }">
              <el-tag :type="roleTagType(row.role)" size="small">
                {{ roleLabel(row.role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数据范围" width="120">
            <template #default="{ row }">
              <span v-if="row.role === 'VILLAGE_ADMIN' && row.villageCode">{{ villageName(row.villageCode) }}</span>
              <span v-else class="cell-muted">全部村镇</span>
            </template>
          </el-table-column>
          <el-table-column prop="phone" label="手机" width="120" />
          <el-table-column prop="email" label="邮箱" />
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '正常' : '禁用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" text size="small" @click="openEditAdmin(row)">编辑</el-button>
              <el-button :type="row.status === 1 ? 'warning' : 'success'" text size="small" @click="toggleAdminStatus(row)">
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button type="danger" text size="small" @click="handleDeleteAdmin(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

    </el-tabs>
  </el-card>

  <!-- 农户详情弹窗 -->
  <el-dialog v-model="detailVisible" title="农户详情" width="500px">
    <div v-loading="detailLoading" class="user-detail" v-if="detailUser">
      <div class="detail-header">
        <el-avatar :size="56" :src="resolveFileUrl(detailUser.avatarUrl) || undefined">
          {{ (detailUser.nickname || '?')[0] }}
        </el-avatar>
        <div class="detail-name-area">
          <div class="detail-name">{{ detailUser.nickname || '未设置昵称' }}</div>
          <el-tag :type="detailUser.status === 1 ? 'success' : 'danger'" size="small">{{ detailUser.status === 1 ? '正常' : '禁用' }}</el-tag>
        </div>
      </div>
      <el-descriptions :column="1" border size="small" class="detail-desc">
        <el-descriptions-item label="用户ID">{{ detailUser.id }}</el-descriptions-item>
        <el-descriptions-item label="OpenID">{{ detailUser.openid }}</el-descriptions-item>
        <el-descriptions-item label="所属村镇">{{ villageName(detailUser.villageCode) }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ detailUser.phone || '未绑定' }}</el-descriptions-item>
        <el-descriptions-item label="最后登录">{{ formatTime(detailUser.lastLoginTime) }}</el-descriptions-item>
      </el-descriptions>
    </div>
    <template #footer>
      <el-button v-if="detailUser" :type="detailUser.status === 1 ? 'danger' : 'success'" @click="toggleUserStatus(detailUser); detailVisible = false">
        {{ detailUser.status === 1 ? '禁用用户' : '启用用户' }}
      </el-button>
      <el-button @click="detailVisible = false">关闭</el-button>
    </template>
  </el-dialog>

  <!-- 管理员编辑弹窗 -->
  <el-dialog v-model="adminDialogVisible" :title="isEdit ? '编辑管理员' : '新增管理员'" width="500px">
    <el-form :model="form" label-width="80px">
      <el-form-item label="账号"><el-input v-model="form.username" :disabled="isEdit" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" :placeholder="isEdit ? '留空不修改' : '请输入密码'" show-password /></el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.role" style="width: 100%">
          <el-option v-for="r in roleOptions" :key="r.value" :label="r.label" :value="r.value" />
        </el-select>
        <div class="form-hint">
          超级管理员可管账号与系统参数；专家处理农情与咨询；村镇管理员只能处理绑定村镇的业务，且不能删除数据。
        </div>
      </el-form-item>
      <!-- 村镇绑定只在「村镇管理员」时出现：它是数据范围的依据，其他角色选了也没用 -->
      <el-form-item v-if="form.role === 'VILLAGE_ADMIN'" label="所属村镇">
        <el-select v-model="form.villageCode" placeholder="请选择村镇" style="width: 100%">
          <el-option v-for="d in villageOptions" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
      <el-form-item v-if="isEdit" label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="正常" inactive-text="禁用" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="adminDialogVisible = false">取消</el-button>
      <el-button type="primary" @click="saveAdmin">保存</el-button>
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

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar {
  background: #e4e7ed;
  color: #606266;
  font-size: 14px;
  flex-shrink: 0;
}

.user-info {
  line-height: 1.4;
}

.user-name {
  font-weight: 600;
  color: #303133;
  font-size: 14px;
}

.user-sub {
  font-size: 12px;
  color: #6B7280;
}

.user-detail {
  padding: 8px 0;
}

.detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.detail-header .el-avatar {
  background: linear-gradient(135deg, #2d6a4f, #52b788);
  color: #fff;
  font-size: 20px;
}

.detail-name-area {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail-name {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.detail-desc {
  margin-top: 4px;
}

/* 表单项下方的补充说明：权限模型的差异必须写在现场，
   不然「村镇管理员为什么还要选村镇」只能靠猜 */
.form-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: #6B7280;
}

.cell-muted {
  color: #c0c4cc;
}
</style>

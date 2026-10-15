<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Check, DocumentAdd } from '@element-plus/icons-vue'
import {
  getPromptTemplates, createPromptTemplate, updatePromptTemplate,
  activatePromptTemplate, deletePromptTemplate,
} from '@/api/admin'
import { useCrudPage } from '@/composables/useCrudPage'
import { useAdminStore } from '@/stores/admin'
import { canEditPrompt, isSuperAdmin } from '@/utils/roles'
import ReadOnlyNote from '@/components/ReadOnlyNote.vue'

/** 提示词模板管理：改完立即生效（2 分钟缓存，写操作主动清），每次改动留成新版本以便对照。 */

/** 各编码的用途说明，写在页面上免得改的时候猜 */
const CODE_HINTS: Record<string, string> = {
  'agri.chat.system': '主系统提示词，每一次 AI 对话都会带上它',
  'agri.chat.knowledge.rules': '命中知识库时追加的约束（要求标注来源、禁止编造药剂剂量）',
}

const adminStore = useAdminStore()
// 编辑（新增/改/新建版本）超管+专家都能做，启用与删除只超管 —— 与后端一致，避免点了吃 403
const writable = computed(() => canEditPrompt(adminStore.role))
const canPublish = computed(() => isSuperAdmin(adminStore.role))

const {
  rows: templates,
  loading,
  dialogVisible,
  isEdit,
  form,
  loadData,
  openCreate,
  openEdit,
  save,
  remove: handleDelete,
} = useCrudPage({
  listApi: getPromptTemplates,
  createApi: createPromptTemplate,
  updateApi: updatePromptTemplate,
  removeApi: deletePromptTemplate,
  defaultForm: () => ({ code: '', name: '', content: '', description: '' }),
  confirmDelete: (row: any) =>
    ElMessageBox.confirm(
      `确定删除「${row.name}」的 v${row.version} 吗？删除后该版本不可恢复。`,
      '删除提示词版本',
      { type: 'warning' },
    ).then(() => true).catch(() => false),
})

/** 按编码分组，组内版本号倒序（最新的在最上面） */
const groups = computed(() => {
  const map = new Map<string, any[]>()
  for (const t of templates.value as any[]) {
    const list = map.get(t.code) ?? []
    list.push(t)
    map.set(t.code, list)
  }
  return [...map.entries()]
    .map(([code, versions]) => {
      // 组内版本号倒序（最新的在最上面）
      const sorted = [...versions].sort((a, b) => b.version - a.version)
      // 真正生效的那条 = 启用中的最大版本（倒序后遇到的第一个就是）。
      // 同一编码下可能有多条 enabled，标出「已启用但未生效」，避免误以为点过启用就切换了
      const active = sorted.find(v => v.enabled)
      return {
        code,
        hint: CODE_HINTS[code] || '自定义模板',
        versions: sorted,
        activeVersion: active ? active.version : null,
      }
    })
    .sort((a, b) => a.code.localeCompare(b.code))
})

/** 在某个版本的基础上开新版本：把正文带出来，改完直接存成下一版 */
function openNewVersion(row: any) {
  isEdit.value = false
  form.value = {
    code: row.code,
    name: row.name,
    content: row.content,
    description: '',
  }
  dialogVisible.value = true
}

/** 启用指定版本（回滚就点它） */
async function activate(row: any) {
  try {
    await activatePromptTemplate(row.id)
    ElMessage.success(`已启用 v${row.version}`)
    await loadData()
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || e?.message || '启用失败')
  }
}

function dialogTitle() {
  if (isEdit.value) return '编辑提示词'
  return form.value.code ? '新建版本' : '新建提示词模板'
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h2 class="page-title">提示词模板</h2>
        <p class="page-desc">管理系统提示词，改完立即生效，每次改动留成一个新版本</p>
      </div>
      <el-button v-if="writable" type="primary" size="large" @click="openCreate">
        <el-icon style="margin-right: 6px"><Plus /></el-icon>
        新建模板
      </el-button>
    </div>

    <ReadOnlyNote
      v-if="!writable"
      text="当前账号只能查看。新建、编辑提示词需要超级管理员或专家；启用与删除需要超级管理员。"
    />

    <el-card
      v-for="g in groups"
      :key="g.code"
      class="table-card group-card"
      shadow="never"
    >
      <template #header>
        <div class="group-header">
          <div class="group-heading">
            <span class="group-code">{{ g.code }}</span>
            <span class="group-hint">{{ g.hint }}</span>
          </div>
          <span class="version-count">{{ g.versions.length }} 个版本</span>
        </div>
      </template>

      <el-table :data="g.versions" v-loading="loading" stripe style="width: 100%">
        <el-table-column label="版本" width="88" align="center">
          <template #default="{ row }">
            <span class="version-tag">v{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.enabled ? (row.version === g.activeVersion ? 'success' : 'warning') : 'info'"
              effect="light"
              round
              size="small"
            >
              {{ row.enabled ? (row.version === g.activeVersion ? '生效中' : '已启用') : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column label="正文" min-width="320">
          <template #default="{ row }">
            <span class="content-preview">{{ row.content }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="130">
          <template #default="{ row }">
            <span class="desc-cell">{{ row.description || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right" align="center">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button v-if="writable" type="primary" text size="small" @click="openEdit(row)">
                <el-icon style="margin-right: 2px"><Edit /></el-icon>编辑
              </el-button>
              <el-button
                v-if="canPublish && !row.enabled"
                type="success"
                text
                size="small"
                @click="activate(row)"
              >
                <el-icon style="margin-right: 2px"><Check /></el-icon>启用
              </el-button>
              <el-button v-if="writable" type="warning" text size="small" @click="openNewVersion(row)">
                <el-icon style="margin-right: 2px"><DocumentAdd /></el-icon>新建版本
              </el-button>
              <el-button v-if="canPublish" type="danger" text size="small" @click="handleDelete(row)">
                <el-icon style="margin-right: 2px"><Delete /></el-icon>删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="groups.length === 0 && !loading" class="table-card" shadow="never">
      <div class="empty-state">
        <el-icon class="empty-icon"><DocumentAdd /></el-icon>
        <p class="empty-title">还没有提示词模板</p>
        <p class="empty-desc">
          新建后 AI 对话会按它生成回答，同一编码下可以并存多个版本。用右上角「新建模板」开始。
        </p>
      </div>
    </el-card>
  </div>

  <el-dialog v-model="dialogVisible" :title="dialogTitle()" width="760px">
    <el-form :model="form" label-width="80px">
      <el-form-item label="编码">
        <el-input
          v-model="form.code"
          :disabled="isEdit || !!form.code"
          placeholder="如 agri.chat.system"
        />
        <div class="field-tip">
          编码是业务代码取模板用的键，创建后不可改；同一编码下版本号自动递增。
        </div>
      </el-form-item>
      <el-form-item label="名称">
        <el-input v-model="form.name" placeholder="如：农业气象助手-系统提示词" />
      </el-form-item>
      <el-form-item label="正文">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="16"
          class="prompt-editor"
          placeholder="提示词正文，支持多行"
        />
      </el-form-item>
      <el-form-item label="变更说明">
        <el-input v-model="form.description" placeholder="这一版改了什么，便于回看" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">
        {{ isEdit ? '保存修改' : '创建版本' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
/* ===== 页面骨架：与其余管理页保持一致 ===== */
.page-container {
  padding: 24px;
  background: #f5f7fa;
  min-height: var(--ad-view-h, calc(100vh - 60px));
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  margin: 0 0 4px 0;
}

.page-desc {
  font-size: 13px;
  color: #6b7280;
  margin: 0;
}

.table-card {
  border-radius: 10px;
  border: 1px solid #ebeef5;
}

/* ===== 分组卡头：编码用等宽徽标（它是代码里的键），用途说明降一级 ===== */
.group-card + .group-card {
  margin-top: 16px;
}

.group-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.group-heading {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.group-code {
  align-self: flex-start;
  font-family: ui-monospace, 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: #2d6a4f;
  background: #eef6f0;
  border: 1px solid #d9e9de;
  border-radius: 6px;
  padding: 2px 8px;
}

.group-hint {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}

.version-count {
  flex-shrink: 0;
  font-size: 12px;
  color: #6b7280;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 999px;
  padding: 3px 10px;
}

.version-tag {
  font-family: ui-monospace, 'SFMono-Regular', Consolas, 'Courier New', monospace;
  font-size: 12.5px;
  font-weight: 600;
  color: #2d6a4f;
  background: #eef6f0;
  border-radius: 6px;
  padding: 2px 8px;
}

.content-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 12px;
  color: #6b7280;
  white-space: pre-wrap;
  line-height: 1.6;
}

/* 说明可能为空，空着会让整列看起来像没渲染，给个占位 */
.desc-cell {
  font-size: 12.5px;
  color: #6b7280;
}

.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 2px;
}

/* ===== 空状态：说清「这是什么、建了会怎样」，并给一个明确的下一步 ===== */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 32px 0 28px;
  text-align: center;
}

.empty-icon {
  font-size: 32px;
  color: #c5d3c9;
  margin-bottom: 4px;
}

.empty-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.empty-desc {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.6;
  max-width: 460px;
}

.field-tip {
  /* form-item 的 content 是 flex 容器，不给满宽会和输入框挤在同一行 */
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.6;
}

/* 提示词正文用等宽字体，缩进和换行才看得清楚 */
.prompt-editor :deep(textarea) {
  font-family: 'Consolas', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.7;
}
</style>

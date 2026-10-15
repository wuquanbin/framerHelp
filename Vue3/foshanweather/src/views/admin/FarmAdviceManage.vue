<script setup lang="ts">
import { ref, computed } from 'vue'
import { getAdminFarmAdvicesPaged, createFarmAdvice, updateFarmAdvice, deleteFarmAdvice, getAdminVillages } from '@/api/admin'
import { ElMessageBox } from 'element-plus'
import { useCrudPage } from '@/composables/useCrudPage'
import { useAdminStore } from '@/stores/admin'
import { canDelete } from '@/utils/roles'
import ReadOnlyNote from '@/components/ReadOnlyNote.vue'
import {
  Sunny, Watermelon, Warning, Box, Sunrise, Cloudy, Drizzling, WindPower,
  Crop, Orange, Cherry, Grape, Apple,
  Bell, InfoFilled, QuestionFilled, Setting, Tools,
  Plus, Search, Edit, Delete
} from '@element-plus/icons-vue'

const iconOptions = [
  { key: 'Sunny', label: '种植', component: Sunny },
  { key: 'Watermelon', label: '灌溉', component: Watermelon },
  { key: 'Warning', label: '虫害', component: Warning },
  { key: 'Box', label: '收获', component: Box },
  { key: 'Sunrise', label: '晴天', component: Sunrise },
  { key: 'Cloudy', label: '多云', component: Cloudy },
  { key: 'Drizzling', label: '雨天', component: Drizzling },
  { key: 'WindPower', label: '大风', component: WindPower },
  { key: 'Crop', label: '小麦', component: Crop },
  { key: 'Orange', label: '玉米', component: Orange },
  { key: 'Cherry', label: '蔬菜', component: Cherry },
  { key: 'Grape', label: '水果', component: Grape },
  { key: 'Apple', label: '花卉', component: Apple },
  { key: 'Bell', label: '生长', component: Bell },
  { key: 'InfoFilled', label: '警告', component: InfoFilled },
  { key: 'QuestionFilled', label: '提醒', component: QuestionFilled },
  { key: 'Setting', label: '设置', component: Setting },
  { key: 'Tools', label: '工具', component: Tools },
]

const iconMap: Record<string, any> = {}
iconOptions.forEach(opt => { iconMap[opt.key] = opt.component })

const legacyIconMap: Record<string, string> = {
  Sprout: 'Sunny',
  Bug: 'Warning',
  Sun: 'Sunrise',
  Cloud: 'Cloudy',
  Rainy: 'Drizzling',
  Wind: 'WindPower',
  Wheat: 'Crop',
  Corn: 'Orange',
  Vegetable: 'Cherry',
  Fruit: 'Grape',
  Flower: 'Apple',
  Leaf: 'Bell',
  Info: 'InfoFilled',
  planting: 'Sunny',
  irrigation: 'Watermelon',
  pest: 'Warning',
  harvest: 'Box',
}

const searchKeyword = ref('')
const filterVillage = ref('')
const filterPriority = ref('')

// 列表/弹窗/保存/删除这套流程交给 useCrudPage，页面只留下农事建议自己的东西：
// 图标选择器（含旧图标键迁移）、前端筛选、表格列与表单字段。
const adminStore = useAdminStore()
const {
  rows: advices,
  loading,
  total,
  page,
  size,
  dialogVisible,
  isEdit,
  form,
  dicts,
  removable,
  loadData,
  handlePageChange,
  handleSizeChange,
  openCreate,
  openEdit,
  save,
  remove: handleDelete,
} = useCrudPage({
  listApi: getAdminFarmAdvicesPaged,
  createApi: createFarmAdvice,
  updateApi: updateFarmAdvice,
  removeApi: deleteFarmAdvice,
  // 农事建议是持续累积的，走服务端分页，避免首屏读整表
  paged: true,
  defaultForm: () => ({
    title: '', summary: '', recommendation: '',
    priorityTag: '中', iconKey: 'Sunny', priority: 5, village: null,
  }),
  dictionaries: { villages: getAdminVillages },
  // 后端 DELETE /api/admin/** 只放行超管+专家，村镇管理员点了必然 403，所以按钮要收起来
  canRemove: () => canDelete(adminStore.role),
  // 编辑时要把历史遗留的图标键迁移到现有的 18 个图标上，否则下拉框匹配不到
  toForm: (row: any) => ({
    ...row,
    iconKey: legacyIconMap[row.iconKey] || row.iconKey || 'Sunny',
    village: row.villageCode || row.village?.code || null,
  }),
  confirmDelete: () =>
    ElMessageBox.confirm('确定删除该农事建议？', '提示', { type: 'warning' })
      .then(() => true)
      .catch(() => false),
})

const filteredAdvices = computed(() => {
  let list = advices.value
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(item =>
      item.title?.toLowerCase().includes(kw) ||
      item.summary?.toLowerCase().includes(kw)
    )
  }
  if (filterVillage.value) {
    list = list.filter(item => item.villageCode === filterVillage.value || item.village?.code === filterVillage.value)
  }
  if (filterPriority.value) {
    list = list.filter(item => item.priorityTag === filterPriority.value)
  }
  return list
})

function iconComponent(key: string) {
  return iconMap[key] || iconMap.Sunny
}

function priorityTagType(tag: string) {
  if (tag === '高') return 'danger'
  if (tag === '中') return 'warning'
  return 'success'
}

function priorityTagText(tag: string) {
  if (tag === '高') return '紧急'
  if (tag === '中') return '重要'
  return '一般'
}

function getIconComponent(key: string) {
  const mappedKey = legacyIconMap[key] || key
  return iconMap[mappedKey] || iconMap['InfoFilled']
}
</script>

<template>
  <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-header">
      <div>
        <h2 class="page-title">农事建议管理</h2>
        <p class="page-desc">管理各村庄的农事建议与生产指导</p>
      </div>
      <el-button type="primary" size="large" @click="openCreate">
        <el-icon style="margin-right: 6px"><Plus /></el-icon>
        新增建议
      </el-button>
    </div>

    <!-- 筛选栏 -->
    <el-card class="filter-card" shadow="never">
      <div class="filter-row">
        <div class="filter-left">
          <el-input
            v-model="searchKeyword"
            placeholder="搜索标题或摘要"
            style="width: 280px"
            clearable
          >
            <template #prefix>
              <el-icon><Search /></el-icon>
            </template>
          </el-input>
          <el-select v-model="filterVillage" placeholder="所属村镇" clearable style="width: 160px">
            <el-option v-for="v in dicts.villages" :key="v.id" :label="v.name" :value="v.code" />
          </el-select>
          <el-select v-model="filterPriority" placeholder="优先级" clearable style="width: 120px">
            <el-option label="紧急" value="高" />
            <el-option label="重要" value="中" />
            <el-option label="一般" value="低" />
          </el-select>
        </div>
        <div class="filter-right">
          <span class="count-text">共 {{ filteredAdvices.length }} 条记录</span>
        </div>
      </div>
    </el-card>

    <!-- 数据表格 -->
    <el-card class="table-card" shadow="never">
      <ReadOnlyNote
        v-if="!canDelete(adminStore.role)"
        text="当前账号可以新增和修改农事建议；删除需要超级管理员或专家权限。"
      />
      <el-table :data="filteredAdvices" v-loading="loading" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column label="图标" width="70" align="center">
          <template #default="{ row }">
            <el-icon :size="20" color="#2d6a4f">
              <component :is="getIconComponent(row.iconKey)" />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="140">
          <template #default="{ row }">
            <div class="title-cell">
              <span class="title-text">{{ row.title }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="优先级" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priorityTag)" effect="light" round>
              {{ priorityTagText(row.priorityTag) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="排序" width="80" align="center">
          <template #default="{ row }">
            <span class="sort-num">{{ row.priority }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属村镇" width="120">
          <template #default="{ row }">
            <span class="village-name">{{ row.villageName || row.village?.name || '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="openEdit(row)">
              <el-icon style="margin-right: 2px"><Edit /></el-icon>编辑
            </el-button>
            <el-button v-if="removable" type="danger" text size="small" @click="handleDelete(row)">
              <el-icon style="margin-right: 2px"><Delete /></el-icon>删除
            </el-button>
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
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>

  <!-- 弹窗 -->
  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑农事建议' : '新增农事建议'" width="680px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="所属村镇">
        <el-select v-model="form.village" placeholder="选择村镇" style="width: 100%">
          <el-option v-for="v in dicts.villages" :key="v.id" :label="v.name" :value="v.code" />
        </el-select>
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="优先级">
            <el-select v-model="form.priorityTag" style="width: 100%">
              <el-option label="紧急（高）" value="高" />
              <el-option label="重要（中）" value="中" />
              <el-option label="一般（低）" value="低" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="排序值">
            <el-input-number v-model="form.priority" :min="1" :max="100" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="标题"><el-input v-model="form.title" placeholder="请输入建议标题" /></el-form-item>
      <el-form-item label="摘要"><el-input v-model="form.summary" type="textarea" :rows="2" placeholder="简短描述" /></el-form-item>
      <el-form-item label="建议内容"><el-input v-model="form.recommendation" type="textarea" :rows="5" placeholder="详细建议内容" /></el-form-item>
      <el-form-item label="选择图标">
        <div class="icon-picker">
          <div
            v-for="opt in iconOptions"
            :key="opt.key"
            class="icon-picker-item"
            :class="{ active: form.iconKey === opt.key }"
            @click="form.iconKey = opt.key"
          >
            <el-icon :size="20">
              <component :is="opt.component" />
            </el-icon>
            <span class="icon-picker-label">{{ opt.label }}</span>
          </div>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.page-container {
  padding: 24px;
  background: #f5f7fa;
  min-height: var(--ad-view-h, calc(100vh - 60px));
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
  color: #6B7280;
  margin: 0;
}

.filter-card {
  border-radius: 10px;
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
}

.filter-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.filter-left {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.filter-right {
  display: flex;
  align-items: center;
}

.count-text {
  font-size: 13px;
  color: #6B7280;
}

.table-card {
  border-radius: 10px;
  border: 1px solid #ebeef5;
}

.title-cell {
  display: flex;
  align-items: center;
}

.title-text {
  font-weight: 500;
  color: #303133;
}

.village-name {
  color: #606266;
}

.sort-num {
  display: inline-block;
  min-width: 32px;
  height: 32px;
  line-height: 32px;
  text-align: center;
  background: #f4f4f5;
  color: #606266;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
}

/* 图标选择器 */
.icon-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 560px;
}

.icon-picker-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  width: 64px;
  padding: 10px 6px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: background-color, border-color, color, box-shadow, opacity 0.2s;
  color: #606266;
}

.icon-picker-item:hover {
  border-color: #2d6a4f;
  color: #2d6a4f;
  background: #f0f9f4;
}

.icon-picker-item.active {
  border-color: #2d6a4f;
  color: #fff;
  background: #2d6a4f;
}

.icon-picker-label {
  font-size: 11px;
  line-height: 1.2;
}
</style>

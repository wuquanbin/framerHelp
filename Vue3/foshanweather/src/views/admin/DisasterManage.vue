<script setup lang="ts">
import { getAdminDisastersPaged, createDisaster, updateDisaster, deleteDisaster, getAdminVillages } from '@/api/admin'
import { ElMessageBox } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import { canDeleteVillageData } from '@/utils/roles'
import { useCrudPage } from '@/composables/useCrudPage'

const adminStore = useAdminStore()

// 列表/弹窗/保存/删除这套流程交给 useCrudPage，
// 页面只留下自己的东西：表格列、表单字段、严重程度的展示规则。
const {
  rows: disasters,
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
  listApi: getAdminDisastersPaged,
  createApi: createDisaster,
  updateApi: updateDisaster,
  removeApi: deleteDisaster,
  // 灾害记录是只增台账，走服务端分页，避免首屏读整表
  paged: true,
  defaultForm: () => ({
    disasterName: '', affectedArea: '', disasterType: '', triggerWeather: '',
    description: '', remedy: '', severity: 1, village: null,
  }),
  dictionaries: { villages: getAdminVillages },
  // 灾害记录同上：村镇可自维护，三类角色都可删；范围由后端 deleteDisaster 的 scope 校验保证
  canRemove: () => canDeleteVillageData(),
  confirmDelete: () =>
    ElMessageBox.confirm('确定删除该灾害记录？', '提示', { type: 'warning' })
      .then(() => true)
      .catch(() => false),
})

function severityType(s: number | string) {
  const n = Number(s)
  if (n >= 4) return 'danger'    // 特大
  if (n === 3) return 'danger'   // 严重
  if (n === 2) return 'warning'   // 中等
  return 'success'                // 一般
}

function severityLabel(s: number | string) {
  const n = Number(s)
  if (n >= 4) return '特大'
  if (n === 3) return '严重'
  if (n === 2) return '中等'
  return '一般'
}
</script>

<template>
  <el-card shadow="hover">
    <template #header>
      <div class="card-header">
        <span>灾害记录管理</span>
        <el-button type="primary" @click="openCreate">新增记录</el-button>
      </div>
    </template>
    <el-table :data="disasters" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="disasterName" label="灾害名" width="120" />
      <el-table-column prop="disasterType" label="类型" width="90" />
      <el-table-column prop="affectedArea" label="受灾区域" width="120" />
      <el-table-column label="严重程度" width="100">
        <template #default="{ row }">
          <el-tag :type="severityType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="triggerWeather" label="触发天气" width="100" />
      <el-table-column label="所属村镇" width="120">
        <template #default="{ row }">{{ row.villageName || row.village?.name || '--' }}</template>
      </el-table-column>
      <el-table-column prop="description" label="描述" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="removable" type="danger" text size="small" @click="handleDelete(row)">删除</el-button>
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

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑灾害记录' : '新增灾害记录'" width="650px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="所属村镇">
        <el-select v-model="form.village" placeholder="选择村镇" style="width: 100%">
          <el-option v-for="v in dicts.villages" :key="v.id" :label="v.name" :value="v.code" />
        </el-select>
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="灾害名称"><el-input v-model="form.disasterName" /></el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="灾害类型">
            <el-select v-model="form.disasterType" style="width: 100%">
              <el-option label="洪涝" value="洪涝" />
              <el-option label="干旱" value="干旱" />
              <el-option label="台风" value="台风" />
              <el-option label="冰雹" value="冰雹" />
              <el-option label="霜冻" value="霜冻" />
              <el-option label="病虫害" value="病虫害" />
              <el-option label="其他" value="其他" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="受灾区域"><el-input v-model="form.affectedArea" placeholder="如：300亩" /></el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="严重程度">
            <el-select v-model="form.severity" style="width: 100%">
              <el-option label="一般" :value="1" />
              <el-option label="中等" :value="2" />
              <el-option label="严重" :value="3" />
              <el-option label="特大" :value="4" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="触发天气"><el-input v-model="form.triggerWeather" placeholder="如：暴雨、台风" /></el-form-item>
      <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
      <el-form-item label="补救措施"><el-input v-model="form.remedy" type="textarea" :rows="3" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>

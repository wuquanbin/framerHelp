<script setup lang="ts">
import { ref, watch } from 'vue'
import { getAdminCropsPaged, createCrop, updateCrop, deleteCrop, getAdminVillages } from '@/api/admin'
import { ElMessageBox } from 'element-plus'
import { riskLabel, riskTagType, RISK_OPTIONS } from '@/utils/risk'
import { useCrudPage } from '@/composables/useCrudPage'
import { useAdminStore } from '@/stores/admin'
import { canDeleteVillageData } from '@/utils/roles'

// 列表/弹窗/保存/删除这套流程交给 useCrudPage，页面只留下作物自己的东西：
// 图片映射、前端筛选、表格列与表单字段。
const adminStore = useAdminStore()

// 筛选条件声明在 useCrudPage 之前：extraParams 和 watch 都要用到它们
const villageFilter = ref('')
const cropTypeFilter = ref('')

/** 作物类型：筛选与表单共用这一份（拆成两处硬编码就会漏掉实际存在的类型，让下拉选不到）。 */
const CROP_TYPES = ['粮食', '蔬菜', '水果', '经济作物', '水产', '其他']

const {
  rows: crops,
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
  listApi: getAdminCropsPaged,
  createApi: createCrop,
  updateApi: updateCrop,
  removeApi: deleteCrop,
  // 作物档案会随村镇增长，走服务端分页，避免首屏读整表
  paged: true,
  // 筛选下推到后端：分页模式下在前端过滤只能筛当前页那几条，计数和分页器会对不上
  extraParams: () => ({
    villageCode: villageFilter.value || undefined,
    cropType: cropTypeFilter.value || undefined,
  }),
  defaultForm: () => ({
    cropName: '', cropType: '', plantingArea: '', growthStage: '',
    // riskLevel 是后端整型 1–5，不能填中文字符串，否则保存时会因反序列化失败报 400
    isMainCrop: false, suitableWeather: '', riskWeather: '', riskLevel: 1, farmingNotes: '', village: null,
  }),
  dictionaries: { villages: getAdminVillages },
  // 作物是村镇自维护的台账，三类角色都可删；删不到别村由后端 scope 拦截
  canRemove: () => canDeleteVillageData(),
  confirmDelete: () =>
    ElMessageBox.confirm('确定删除该作物档案？', '提示', { type: 'warning' })
      .then(() => true)
      .catch(() => false),
})

// 作物图片映射（与小程序端 farming 页共用同一套图，存放于 public/crops）
const CROP_IMAGES: Record<string, string> = {
  '丝苗米': '/crops/rice-simiaomi.png',
  '早稻': '/crops/rice-early.png',
  '晚稻': '/crops/rice-late.png',
  '黑皮冬瓜': '/crops/winter-melon.png',
  '菜心': '/crops/choy-sum.png',
  '生菜': '/crops/lettuce.png',
  '粉葛': '/crops/kudzu.png',
  '单丛茶': '/crops/dancong-tea.png',
  '茶叶': '/crops/dancong-tea.png',
  '荔枝': '/crops/lychee.png',
  '百合花': '/crops/lily.png',
  '蝴蝶兰': '/crops/phalaenopsis.png',
  '兰花': '/crops/orchid.png',
  '年桔': '/crops/new-year-orange.png',
  '莲藕': '/crops/lotus-root.png',
  '桂花鱼': '/crops/mandarin-fish.png',
}

// 作物名没匹配上时，按作物类型显示兜底图
const TYPE_FALLBACK_IMAGES: Record<string, string> = {
  '粮食': '/crops/rice-late.png',
  '蔬菜': '/crops/choy-sum.png',
  '水果': '/crops/lychee.png',
  '经济作物': '/crops/lily.png',
  '水产': '/crops/mandarin-fish.png',
  '其他': '/crops/lotus-root.png',
}

function cropImageOf(row: any): string {
  return CROP_IMAGES[row.cropName] || TYPE_FALLBACK_IMAGES[row.cropType] || '/crops/lotus-root.png'
}

// 筛选条件变化 → 回到第一页重新拉取（条件由后端执行，前端不再本地过滤）
watch([villageFilter, cropTypeFilter], () => {
  page.value = 1
  loadData()
})

</script>

<template>
  <el-card shadow="hover">
    <template #header>
      <div class="card-header">
        <span>作物档案管理</span>
        <el-button type="primary" @click="openCreate">新增作物</el-button>
      </div>
    </template>
    <div class="filter-bar">
      <el-select v-model="villageFilter" placeholder="村镇筛选" clearable style="width: 140px">
        <el-option v-for="v in dicts.villages" :key="v.code" :label="v.name" :value="v.code" />
      </el-select>
      <el-select v-model="cropTypeFilter" placeholder="作物类型" clearable style="width: 140px">
        <el-option v-for="t in CROP_TYPES" :key="t" :label="t" :value="t" />
      </el-select>
      <span class="filter-count">共 {{ total }} 条</span>
    </div>
    <el-table :data="crops" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column label="图片" width="76" align="center">
        <template #default="{ row }">
          <el-image
            class="crop-thumb"
            :src="cropImageOf(row)"
            :preview-src-list="[cropImageOf(row)]"
            preview-teleported
            fit="cover"
          />
        </template>
      </el-table-column>
      <el-table-column prop="cropName" label="作物名" width="120" />
      <el-table-column prop="cropType" label="类型" width="90" />
      <el-table-column prop="plantingArea" label="种植面积" width="100" />
      <el-table-column prop="growthStage" label="生长期" width="100" />
      <el-table-column label="风险等级" width="104">
        <template #default="{ row }">
          <el-tag :type="riskTagType(row.riskLevel)" size="small" effect="light" round>
            {{ riskLabel(row.riskLevel) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="主要作物" width="80">
        <template #default="{ row }">
          <el-tag :type="row.isMainCrop ? 'success' : 'info'" size="small">{{ row.isMainCrop ? '是' : '否' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="所属村镇" width="120">
        <template #default="{ row }">{{ row.villageName || row.village?.name || '--' }}</template>
      </el-table-column>
      <el-table-column prop="farmingNotes" label="农事备注" show-overflow-tooltip />
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

  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑作物' : '新增作物'" width="650px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="所属村镇">
        <el-select v-model="form.village" placeholder="选择村镇" style="width: 100%">
          <el-option v-for="v in dicts.villages" :key="v.id" :label="v.name" :value="v.code" />
        </el-select>
      </el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="作物名称"><el-input v-model="form.cropName" /></el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="作物类型">
            <el-select v-model="form.cropType" style="width: 100%">
              <el-option v-for="t in CROP_TYPES" :key="t" :label="t" :value="t" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="种植面积"><el-input v-model="form.plantingArea" placeholder="如：500亩" /></el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="生长阶段">
            <el-select v-model="form.growthStage" style="width: 100%">
              <el-option label="播种期" value="播种期" />
              <el-option label="生长期" value="生长期" />
              <el-option label="开花期" value="开花期" />
              <el-option label="结实期" value="结实期" />
              <el-option label="收获期" value="收获期" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="主要作物"><el-switch v-model="form.isMainCrop" /></el-form-item>
      <el-form-item label="适宜天气"><el-input v-model="form.suitableWeather" placeholder="如：晴朗、微风" /></el-form-item>
      <el-form-item label="风险天气"><el-input v-model="form.riskWeather" placeholder="如：暴雨、高温" /></el-form-item>
      <el-form-item label="风险等级">
        <el-select v-model="form.riskLevel" style="width: 100%">
          <el-option v-for="o in RISK_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="农事备注"><el-input v-model="form.farmingNotes" type="textarea" :rows="3" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.filter-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.filter-count { font-size: 13px; color: #6B7280; margin-left: auto; }
.crop-thumb {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  display: block;
  margin: 0 auto;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
  transition: transform 0.2s ease;
  cursor: pointer;
}
.crop-thumb:hover { transform: scale(1.12); }
</style>

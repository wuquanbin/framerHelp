import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 单表 CRUD 页面的公共逻辑
 * <p>
 * 后台有五个「列表 + 弹窗表单 + 增删改」的页面，以前每个页面各写一遍
 * loading / 分页 / 打开弹窗 / 保存 / 删除确认，加起来两千行重复代码。
 * 这里把**流程**抽出来，**表格列与表单字段仍然留在各自的模板里** ——
 * 那些是差异所在，硬塞进配置对象只会变成一堆 v-if，可读性反而更差。
 * </p>
 *
 * <p>刻意没有覆盖的两类页面：</p>
 * <ul>
 *   <li>{@code VillageManage}：挂了 900 行 three.js 地球选址，还有级联强删（force），
 *       抽象进来会让这里长出特殊分支，得不偿失。</li>
 *   <li>{@code FeedbackManage}：根本没有新增/删除，只有「回复」和「标记解决」，不是 CRUD。</li>
 * </ul>
 */

/** 字典数据源：key 为字典名，value 为拉取函数（如 { villages: getAdminVillages }） */
type Dictionaries = Record<string, () => Promise<any>>

export interface UseCrudPageOptions {
  /** 列表查询。分页模式下返回 { items, total }，全量模式返回数组 */
  listApi: (params?: any) => Promise<any>
  /** 新增接口 */
  createApi?: (data: any) => Promise<any>
  /** 更新接口（id, 全量表单） */
  updateApi?: (id: number, data: any) => Promise<any>
  /** 删除接口 */
  removeApi?: (id: number) => Promise<any>
  /** 新增时表单的初始值（必须返回新对象，别共享同一个引用） */
  defaultForm: () => Record<string, any>
  /** 是否服务端分页，默认 false（一次性拉全量，由页面自行前端过滤） */
  paged?: boolean
  /** 每页大小，默认 20 */
  pageSize?: number
  /** 额外的查询参数，每次拉列表时合并进去（如状态筛选） */
  extraParams?: () => Record<string, any>
  /**
   * 编辑时把行数据转成表单。
   * 默认行为：浅拷贝 + 把 village 对象归一化成 code 字符串
   * （后端有时返回 Village 对象，而下拉框的 value 是 code，不归一化会导致回显不匹配）。
   */
  toForm?: (row: any) => Record<string, any>
  /** 字典数据，挂在返回的 dicts 上供下拉框使用 */
  dictionaries?: Dictionaries
  /** 删除前的确认文案，返回 false 表示放弃删除 */
  confirmDelete?: (row: any) => Promise<boolean>
  /** 是否有删除权限（用于在表格里隐藏按钮） */
  canRemove?: () => boolean
}

/** 统一的错误提示：优先用后端返回的人话，其次才是异常信息 */
function errorOf(e: any, fallback: string): string {
  return e?.response?.data?.message || e?.message || fallback
}

export function useCrudPage(options: UseCrudPageOptions) {
  const {
    listApi, createApi, updateApi, removeApi,
    defaultForm, paged = false, pageSize = 20,
    extraParams, toForm, dictionaries, confirmDelete, canRemove,
  } = options

  const rows = ref<any[]>([])
  const loading = ref(false)
  const dialogVisible = ref(false)
  const isEdit = ref(false)
  const submitting = ref(false)
  const form = ref<Record<string, any>>(defaultForm())

  // 分页：el-pagination 从 1 起始，后端 page 从 0 起始，请求时减 1
  const page = ref(1)
  const size = ref(pageSize)
  const total = ref(0)

  /** 字典：{ villages: ref([]) }，模板里用 dicts.villages 取 */
  const dicts = reactive<Record<string, any>>({})

  async function loadData() {
    loading.value = true
    try {
      if (paged) {
        const res = await listApi({ page: page.value - 1, size: size.value, ...(extraParams?.() ?? {}) })
        rows.value = res?.items ?? []
        total.value = res?.total ?? 0
      } else {
        rows.value = (await listApi()) ?? []
      }
    } catch (e: any) {
      ElMessage.error(errorOf(e, '加载失败'))
    } finally {
      loading.value = false
    }
  }

  function handlePageChange(p: number) {
    page.value = p
    loadData()
  }

  function handleSizeChange(s: number) {
    size.value = s
    page.value = 1
    loadData()
  }

  function openCreate() {
    isEdit.value = false
    form.value = defaultForm()
    dialogVisible.value = true
  }

  /** 默认的编辑回填：village 可能是对象也可能是 code，统一成 code */
  function defaultToForm(row: any): Record<string, any> {
    const v = row?.village
    const villageCode = typeof v === 'object' && v !== null ? v.code : (v || '')
    return { ...row, village: villageCode }
  }

  function openEdit(row: any) {
    isEdit.value = true
    form.value = toForm ? toForm(row) : defaultToForm(row)
    dialogVisible.value = true
  }

  async function save() {
    if (submitting.value) return
    submitting.value = true
    try {
      if (isEdit.value) {
        if (!updateApi) return
        await updateApi(form.value.id, form.value)
      } else {
        if (!createApi) return
        await createApi(form.value)
      }
      ElMessage.success(isEdit.value ? '更新成功' : '创建成功')
      dialogVisible.value = false
      await loadData()
    } catch (e: any) {
      ElMessage.error(errorOf(e, '操作失败'))
    } finally {
      submitting.value = false
    }
  }

  /** 删除：confirm 被取消时会 reject，单独捕获后静默返回 */
  async function remove(row: any) {
    if (!removeApi) return
    const ok = confirmDelete
      ? await confirmDelete(row)
      : await ElMessageBox.confirm('确定删除该条记录？', '提示', { type: 'warning' })
          .then(() => true)
          .catch(() => false)
    if (!ok) return
    try {
      await removeApi(row.id ?? row)
      ElMessage.success('已删除')
      // 删掉当前页最后一条时回退一页，避免出现空白页
      if (paged && rows.value.length === 1 && page.value > 1) {
        page.value -= 1
      }
      await loadData()
    } catch (e: any) {
      ElMessage.error(errorOf(e, '删除失败'))
    }
  }

  /** 模板里控制删除按钮显隐；没传 canRemove 时默认可删 */
  const removable = computed(() => (canRemove ? canRemove() : !!removeApi))

  onMounted(async () => {
    // 字典失败不能拖垮页面：下拉框没选项而已，列表照常展示
    if (dictionaries) {
      await Promise.all(
        Object.entries(dictionaries).map(async ([key, fn]) => {
          try {
            dicts[key] = (await fn()) ?? []
          } catch {
            dicts[key] = []
          }
        })
      )
    }
    await loadData()
  })

  return {
    rows, loading, submitting, total, page, size,
    dialogVisible, isEdit, form, dicts, removable,
    loadData, handlePageChange, handleSizeChange,
    openCreate, openEdit, save, remove,
  }
}

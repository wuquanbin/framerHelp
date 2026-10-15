<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as THREE from 'three'
import { getAdminVillages, createVillage, updateVillage, deleteVillage, searchAmapPlace, getAmapStaticMap, type AmapPlace } from '@/api/admin'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAdminStore } from '@/stores/admin'
import { canDelete, canEditVillage } from '@/utils/roles'
import ReadOnlyNote from '@/components/ReadOnlyNote.vue'

const adminStore = useAdminStore()
const villages = ref<any[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const form = ref<any>({
  code: '', name: '', mainCrops: '', terrain: '', assistanceLevel: '一般',
})

// ===== 3D 地球地图选址弹窗 =====
const earthVisible = ref(false)
const earthSearching = ref(false)
const earthDescending = ref(false)
const earthResults = ref<AmapPlace[]>([])
const earthKeyword = ref('')
const earthCanvasRef = ref<HTMLCanvasElement | null>(null)

// 破云降落：全屏云雾遮罩（俯冲时由 three 时间线推上来，切到城市地图后由 dissipateVeil 散去）
const cloudVeilOpacity = ref(0)
const veilLift = ref(0) // 云雾"上飘 + 微放大"的消散感

// 降落后的静态地图
const earthMapUrl = ref('')
const earthMapLoading = ref(false)
const selectedPlace = ref<AmapPlace | null>(null)

/**
 * Three.js 场景引用。⚠️ 除响应式读数外，所有 three 对象都用普通变量持有，
 * 绝不能放进 Vue 的响应式系统（会带来明显的性能损耗）。
 */
let threeScene: THREE.Scene | null = null
let threeCamera: THREE.PerspectiveCamera | null = null
let threeRenderer: THREE.WebGLRenderer | null = null
let earthGroup: THREE.Group | null = null      // 地球整体（自转 + 拖拽偏移都作用在它上面）
let landPoints: THREE.Points | null = null     // 陆地点阵
let gridLines: THREE.LineSegments | null = null // 经纬网
let coreMesh: THREE.Mesh | null = null         // 内层实心球，遮住背面点阵
let atmosphereMesh: THREE.Mesh | null = null   // 大气辉光
let markerDot: THREE.Points | null = null      // 目标地标记点
let markerPulses: THREE.Mesh[] = []            // 目标地脉冲环
let cloudPoints: THREE.Points | null = null    // 破云云壳（俯冲时相机从这层云里穿过去）
let animationFrameId = 0

// 交互状态（同样是普通变量）
let spinAngle = 0         // 自转累计角度
let dragOffsetY = 0       // 拖拽水平偏移
let dragOffsetX = 0       // 拖拽俯仰偏移
let dragVelocity = 0      // 松手后的惯性
let isDragging = false
let descendProgress = 0   // 降落动画进度 0→1（按真实时钟推进，不逐帧累加）
let diveT0 = 0            // 俯冲起始时刻（performance.now()；0 = 不在俯冲）
let retreatT0 = 0         // 俯冲中止（无结果/出错）后的退回起始时刻
let retreatFrom = 0       // 退回开始那一瞬的进度
let veilDissipateId = 0   // 云雾消散动画的 rAF 句柄
let lastHudSync = 0

/** HUD 上的读数（节流更新，不要每帧写响应式） */
const earthFacingLon = ref(113.12)
const facingLonText = computed(
  () => `${Math.abs(earthFacingLon.value).toFixed(1)}°${earthFacingLon.value >= 0 ? 'E' : 'W'}`
)

/** 地球正面对准的经度 —— 服务区在佛山，直接把它设为球体正面 */
const HOME_LON = 113.12
const HOME_LAT = 23.02

onMounted(loadData)

async function loadData() {
  loading.value = true
  try { villages.value = await getAdminVillages() } catch { ElMessage.error('加载失败') }
  finally { loading.value = false }
}

function openCreate() {
  isEdit.value = false
  form.value = { code: '', name: '', mainCrops: '', terrain: '', assistanceLevel: '一般', latitude: null, longitude: null, adcode: '' }
  dialogVisible.value = true
}

function openEdit(row: any) {
  isEdit.value = true
  form.value = { ...row }
  dialogVisible.value = true
}

async function save() {
  try {
    // 编码自动生成：优先用地图搜索带回的 adcode，否则生成唯一编码
    if (!isEdit.value && !form.value.code) {
      form.value.code = 'v' + Date.now().toString(36)
    }
    if (isEdit.value) await updateVillage(form.value.id, form.value)
    else await createVillage(form.value)
    ElMessage.success(isEdit.value ? '更新成功' : '创建成功')
    dialogVisible.value = false
    loadData()
  } catch { ElMessage.error('操作失败') }
}

async function handleDelete(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该村镇？', '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteVillage(id)
    ElMessage.success('已删除')
    loadData()
  } catch (e: any) {
    // 存在关联数据被拦截：询问是否级联删除
    const detail = e?.response?.data?.message || e?.message || '删除失败'
    if (!detail.includes('请先删除相关数据')) {
      ElMessage.error(detail)
      return
    }
    try {
      await ElMessageBox.confirm(
        `${detail}。是否连同这些关联数据一起强制删除？`,
        '强制删除确认',
        { type: 'warning', confirmButtonText: '强制删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
      )
    } catch { return }
    try {
      await deleteVillage(id, true)
      ElMessage.success('已连同关联数据一并删除')
      loadData()
    } catch (e2: any) {
      ElMessage.error(e2?.response?.data?.message || e2?.message || '删除失败')
    }
  }
}

function levelType(level: string) {
  if (level === '重点') return 'danger'
  if (level === '一般') return 'warning'
  return 'success'
}

/**
 * 复位地球视角（佛山正对镜头）。
 * 不能只依赖 initThreeEarth：从结果视图返回地球时场景未必重建，
 * 残留的拖拽偏移会让用户回来看到一颗"歪着的"球。
 */
function resetEarthView() {
  spinAngle = 0
  dragOffsetY = 0
  dragOffsetX = 0
  dragVelocity = 0
  descendProgress = 0
  diveT0 = 0
  retreatT0 = 0
  retreatFrom = 0
  camDist = CAM_D0
  pointsReveal = 0
  pointsBornAt = performance.now()
  earthFacingLon.value = HOME_LON
}

// ===== 地球地图选址 =====
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

function openEarth() {
  earthVisible.value = true
  earthResults.value = []
  earthKeyword.value = ''
  earthDescending.value = false
  cloudVeilOpacity.value = 0
  veilLift.value = 0
  earthMapUrl.value = ''
  selectedPlace.value = null
  resetEarthView()
  // DOM 更新后初始化 Three.js（若已存在则跳过）
  nextTick(() => {
    if (earthCanvasRef.value && !threeRenderer) initThreeEarth(earthCanvasRef.value)
  })
}

// 地球 canvas 从 DOM 插入/移除时管理 Three.js 生命周期（结果视图会移除地球）
watch(earthCanvasRef, (canvas) => {
  if (canvas) {
    if (earthVisible.value && !threeRenderer) initThreeEarth(canvas)
  } else {
    disposeThreeEarth()
  }
})

function closeEarth() {
  disposeThreeEarth()
  earthVisible.value = false
  earthSearching.value = false
  earthDescending.value = false
  releaseMapUrl()
  selectedPlace.value = null
}

/** 结果视图 → 返回地球视图（地球重新挂载时 watch 会重建场景） */
function backToEarth() {
  // 反向穿云：先用云雾盖住视图切换的硬切，等地球场景重建好再让云雾散去
  cloudVeilOpacity.value = 0.92
  veilLift.value = 0
  earthResults.value = []
  releaseMapUrl()
  selectedPlace.value = null
  earthDescending.value = false
  resetEarthView()
  dissipateVeil(950, 300)
}

/** 释放静态地图 ObjectURL，避免内存泄漏 */
function releaseMapUrl() {
  if (earthMapUrl.value) {
    URL.revokeObjectURL(earthMapUrl.value)
    earthMapUrl.value = ''
  }
}

/** 加载指定地点的静态地图图片 */
async function loadMapForPlace(place: AmapPlace) {
  releaseMapUrl()
  earthMapLoading.value = true
  try {
    earthMapUrl.value = await getAmapStaticMap(place.location)
  } catch {
    ElMessage.warning('地图图片加载失败，可继续从列表选择')
  } finally {
    earthMapLoading.value = false
  }
}

/**
 * 云雾消散：穿出云层、露出地表（高德城市地图）。
 * 独立于 three 的生命周期跑 —— 此时地球场景多半已随视图切换被卸载。
 */
function dissipateVeil(duration = 1100, delay = 0) {
  if (veilDissipateId) cancelAnimationFrame(veilDissipateId)
  const run = () => {
    // 弹窗已关 / 又开始了新一轮俯冲：这次消散作废（时间线会接管云雾）
    if (!earthVisible.value || earthDescending.value) return
    const t0 = performance.now()
    const step = () => {
      if (!earthVisible.value || earthDescending.value) {
        veilDissipateId = 0
        return
      }
      const u = Math.min(1, (performance.now() - t0) / duration)
      const e = 1 - Math.pow(1 - u, 3) // easeOutCubic：刚穿出云时散得快
      cloudVeilOpacity.value = 1 - e
      veilLift.value = e
      if (u < 1) veilDissipateId = requestAnimationFrame(step)
      else veilDissipateId = 0
    }
    veilDissipateId = requestAnimationFrame(step)
  }
  if (delay > 0) setTimeout(run, delay)
  else run()
}

/** 等静态地图 <img> 真正解码完 —— 否则云雾散开时露出来的是"地图加载中"占位符 */
async function waitForMapImage() {
  await nextTick()
  const img = document.querySelector<HTMLImageElement>('.earth-dialog .map-img')
  if (!img) return
  try { await img.decode() } catch { /* 已在屏或不支持 decode 时直接继续 */ }
}

async function doEarthSearch() {
  if (!earthKeyword.value.trim()) {
    ElMessage.warning('请输入搜索关键词')
    return
  }
  if (veilDissipateId) {
    cancelAnimationFrame(veilDissipateId)
    veilDissipateId = 0
  }
  // 回到地球视图（若当前在结果视图，地球会重新挂载）
  resetEarthView()
  earthResults.value = []
  releaseMapUrl()
  selectedPlace.value = null
  cloudVeilOpacity.value = 0
  veilLift.value = 0
  earthSearching.value = true
  await nextTick()

  // 请求先发出去，让网络与降落动画并行 —— 网络慢时就在云里多待一会儿
  const pending = searchAmapPlace(earthKeyword.value.trim(), '佛山').catch(() => null)
  await sleep(260)
  const diveStart = performance.now()
  earthDescending.value = true

  try {
    const res = await pending
    // 无论网络多快，都等到俯冲时间线走到"云雾全白"再切换视图
    const remain = VEIL_COVER_MS + 120 - (performance.now() - diveStart)
    if (remain > 0) await sleep(remain)
    if (res === null) throw new Error('search failed')
    const list = res || []
    if (list.length === 0) {
      // 无结果：俯冲中止，云雾随退回动画自然散去，地球重新露面
      earthDescending.value = false
      ElMessage.info('未找到匹配地点')
      return
    }
    // 在云雾最浓处完成视图交接：地球卸载、结果视图在雾后面就位
    earthResults.value = list
    selectedPlace.value = list[0]
    earthSearching.value = false
    await nextTick()
    await loadMapForPlace(list[0])
    await waitForMapImage()
    // 降落结束：复位俯冲标志（dissipateVeil 的守卫见 earthDescending=true 会放弃消散）
    earthDescending.value = false
    // 穿出云层：云雾上飘散去，露出城市地图
    dissipateVeil(1150)
  } catch {
    earthDescending.value = false
    ElMessage.error('地图搜索失败')
  } finally {
    earthSearching.value = false
  }
}

/** 点击列表项：切换地图位置并选中 */
function pickPlace(place: AmapPlace) {
  selectedPlace.value = place
  loadMapForPlace(place)
}

/** 确认选定：回填村镇名称、经纬度和行政区编码 */
function confirmPlace() {
  if (!selectedPlace.value) return
  form.value.name = selectedPlace.value.name
  // 保存经纬度（location 格式 "lng,lat"），供后端按村庄自身坐标拉取天气数据
  const [lng, lat] = (selectedPlace.value.location || '').split(',').map(Number)
  form.value.longitude = Number.isFinite(lng) ? lng : null
  form.value.latitude = Number.isFinite(lat) ? lat : null
  // 高德POI搜索可能不返回adcode；编码留空交给 save() 自动生成唯一编码
  form.value.adcode = selectedPlace.value.adcode || ''
  form.value.code = selectedPlace.value.adcode || ''
  ElMessage.success(`已选择：${selectedPlace.value.name}`)
  closeEarth()
}

// ===== 点阵测绘地球（Three.js）=====
//
// 设计取向：不做"蓝色弹珠 + 云层 + 蓝色辉光"那套通用科技感，
// 改用点阵表达陆地的测绘球 —— 陆地由光点构成、海洋留给内层实心球，
// 配色取品牌深绿与稻金（低海拔青绿 → 高原稻金 → 雪线冷白），
// 并标出北回归线（服务区佛山就在这条线附近）。

/** 各层半径（世界单位），相机距离与辉光尺寸都以此为准 */
const EARTH_R = 1
const GRID_R = 0.9985
const CORE_R = 0.982
const ATMOS_R = 1.1
/** 视场角。用长焦（小 fov）而不是拉近相机，透视变形更小、球体轮廓更完整 */
const EARTH_FOV = 30
/** 相机距离：初始远观 → 俯冲终点（扎进云壳内侧、贴近地表） */
const CAM_D0 = 4.3
const CAM_D1 = 1.03
/** 俯冲时间线（毫秒）：全程时长 / 云雾全白时刻 / 中止退回时长 */
const DIVE_MS = 2400
const VEIL_COVER_MS = 2150
const RETREAT_MS = 950
/** 球面布点数。不是越多越好：点太密会糊成一片，反而看不出"点阵" */
const DOT_COUNT = 20000

let camDist = CAM_D0
let pointsReveal = 0            // 点阵淡入进度
let pointsBornAt = 0            // 点阵生成时刻，淡入按时钟推进而非逐帧累加
let clock: THREE.Clock | null = null
let earthResizeObserver: ResizeObserver | null = null
let unbindPointer: (() => void) | null = null

/**
 * 经纬度 → 单位球坐标。
 * 约定 HOME_LON（113.12°E，佛山）落在 +Z 正前方、正对相机。
 * 掩码采样与标记点放置必须共用这一套映射，否则点位会与地形错位。
 */
function lonLatToVec3(lon: number, lat: number, radius = EARTH_R): THREE.Vector3 {
  const d = ((lon - HOME_LON) * Math.PI) / 180
  const la = (lat * Math.PI) / 180
  return new THREE.Vector3(
    radius * Math.cos(la) * Math.sin(d),
    radius * Math.sin(la),
    radius * Math.cos(la) * Math.cos(d)
  )
}

/** 单位向量 → 经纬度。与 lonLatToVec3 严格互逆，供掩码采样使用。 */
function vec3ToLonLat(x: number, y: number, z: number) {
  const lat = (Math.asin(THREE.MathUtils.clamp(y, -1, 1)) * 180) / Math.PI
  const lon = HOME_LON + (Math.atan2(x, z) * 180) / Math.PI
  return { lon, lat }
}

/** 斐波那契球面均匀布点：等面积分布，不会像经纬网格那样在极点堆积 */
function fibonacciSphere(count: number): Float32Array {
  const arr = new Float32Array(count * 3)
  const ga = Math.PI * (3 - Math.sqrt(5))
  for (let i = 0; i < count; i++) {
    const y = 1 - ((i + 0.5) * 2) / count
    const r = Math.sqrt(Math.max(0, 1 - y * y))
    const th = ga * i
    arr[i * 3] = Math.cos(th) * r
    arr[i * 3 + 1] = y
    arr[i * 3 + 2] = Math.sin(th) * r
  }
  return arr
}

// ===== 陆地 / 高程掩码 =====
// 复用项目里已有的 NASA 蓝弹珠图当"数据源"：它虽是贴图，但像素本身就
// 携带海陆信息，比手绘椭圆大陆准确得多；地形图则提供高程用于配色。

const MASK_W = 1024
const MASK_H = 512
let landMask: Uint8ClampedArray | null = null
let topoMask: Uint8ClampedArray | null = null
/** 海陆判定阈值：蓝通道减红通道超过它即为海洋 */
let landCut = 34
/** 高程分档阈值，按实际数据自适应 */
const topoCut = { mid: 55, high: 130 }

/** 经纬度 → 掩码像素下标（等距圆柱投影，经度 0 落在图像横向中点） */
function maskOffset(lon: number, lat: number): number {
  let u = (lon + 180) / 360
  u -= Math.floor(u)
  const x = Math.min(MASK_W - 1, Math.max(0, Math.floor(u * MASK_W)))
  const y = Math.min(MASK_H - 1, Math.max(0, Math.floor((1 - (lat + 90) / 180) * MASK_H)))
  return (y * MASK_W + x) * 4
}

/** 把图片重采样为掩码像素数据 */
function readMask(img: HTMLImageElement): Uint8ClampedArray {
  const c = document.createElement('canvas')
  c.width = MASK_W
  c.height = MASK_H
  const ctx = c.getContext('2d', { willReadFrequently: true })!
  ctx.drawImage(img, 0, 0, MASK_W, MASK_H)
  return ctx.getImageData(0, 0, MASK_W, MASK_H).data
}

/**
 * 判定某经纬度是否为陆地。
 * 蓝弹珠图带云层，云是纯白高亮像素 —— 若不排除，中低纬度的大陆边缘会被
 * 云"吹胖"成一团。极地冰盖本身就是陆地，所以只在 |lat| < 55 时排云。
 */
function isLand(lon: number, lat: number): boolean {
  if (!landMask) return false
  const i = maskOffset(lon, lat)
  const r = landMask[i]
  const g = landMask[i + 1]
  const b = landMask[i + 2]
  if (r + g + b < 60) return false // 纯黑（无数据区）
  /*
   * 排云阈值取 208 而不是 186：蓝弹珠里亚洲东南部常压着大片薄云，
   * 阈值定低了会把整个华南、东南亚挖空 —— 而佛山正是这个系统的服务区。
   * 宁可放过几缕薄云，也要保证大陆轮廓连续。
   */
  if (Math.abs(lat) < 75 && (r + g + b) / 3 > 208) return false
  return b - r < landCut
}

/** 取该点的高程灰度（0–255），无数据返回 0 */
function elevation(lon: number, lat: number): number {
  if (!topoMask) return 0
  const i = maskOffset(lon, lat)
  return (topoMask[i] + topoMask[i + 1] + topoMask[i + 2]) / 3
}

/**
 * 按陆地样本重新标定海陆与高程阈值。
 * 不能直接统计整图：地形图里海洋也占大面积，会把分位数整体拉低。
 */
function calibrateMasks() {
  if (!landMask) return
  // 海陆阈值：先看整图蓝绿差的均值 —— 海洋偏蓝会把它拉高，陆地样本会拉低
  if (topoMask) {
    const hist = new Uint32Array(256)
    const N = 14000
    const sphere = fibonacciSphere(N)
    let total = 0
    for (let i = 0; i < N; i++) {
      const { lon, lat } = vec3ToLonLat(sphere[i * 3], sphere[i * 3 + 1], sphere[i * 3 + 2])
      if (!isLand(lon, lat)) continue
      hist[Math.round(elevation(lon, lat))]++
      total++
    }
    if (total) {
      let acc = 0
      let pMid = 34
      let pHigh = 118
      for (let i = 0; i < 256; i++) {
        acc += hist[i]
        /*
         * 取 p70 / p95 而不是 p55 / p90：陆地高程是长尾分布，大半陆地都在
         * 30 以下。阈值取低了会让整个北半球平原都染成金白色，青绿反而成了
         * 少数 —— 那正好和"地球该是绿的"相反。
         */
        if (acc / total <= 0.7) pMid = i
        if (acc / total <= 0.95) pHigh = i
      }
      topoCut.mid = pMid
      topoCut.high = Math.max(pMid + 16, pHigh)
    }
  }
}

/** 加载海陆 / 高程两张掩码图，就绪后重建点阵 */
function loadMasks() {
  const load = (src: string, apply: (d: Uint8ClampedArray) => void) => {
    const img = new Image()
    img.onload = () => {
      apply(readMask(img))
      calibrateMasks()
      refreshLandPoints()
    }
    img.onerror = () => {
      // 掩码缺失不阻塞使用：地球仍可拖动、经纬网仍可作参照
      console.warn('[地球] 掩码图加载失败：', src)
    }
    img.src = src
  }
  load('/earth/earth-blue-marble.jpg', (d) => {
    landMask = d
    // 蓝弹珠的海洋偏深蓝、陆地偏绿棕，用蓝绿差的均值把阈值定出来
    let sum = 0
    let n = 0
    for (let i = 0; i < d.length; i += 4 * 97) {
      const r = d[i]
      const g = d[i + 1]
      const b = d[i + 2]
      if (r + g + b < 60) continue
      sum += b - r
      n++
    }
    const avg = n ? sum / n : 55
    landCut = THREE.MathUtils.clamp(avg * 1.25, 22, 62)
  })
  load('/earth/earth-topology.png', (d) => {
    topoMask = d
  })
}

/** 重建点阵时显式释放上一份 geometry 与材质，避免显存泄漏 */
function refreshLandPoints() {
  if (!earthGroup) return
  if (landPoints) {
    earthGroup.remove(landPoints)
    landPoints.geometry.dispose()
    ;(landPoints.material as THREE.Material).dispose()
    landPoints = null
  }
  pointsReveal = 0
  pointsBornAt = performance.now()
  landPoints = buildLandPoints()
  earthGroup.add(landPoints)
}

/**
 * 构建陆地点阵。
 * 点色按高程三档过渡：平原青绿 → 高原稻金 → 雪线冷白，
 * 于是青藏高原、安第斯这类地方会自然"亮起来"，不需要额外描边。
 */
function buildLandPoints(): THREE.Points {
  const COUNT = DOT_COUNT
  const sphere = fibonacciSphere(COUNT)
  const pos: number[] = []
  const col: number[] = []

  const jade = new THREE.Color('#6ed9a0')
  const gold = new THREE.Color('#e8c98a')
  const snow = new THREE.Color('#eaf6ef')
  const ocean = new THREE.Color('#0c2317')
  const tmp = new THREE.Color()
  let seed = 12345

  for (let i = 0; i < COUNT; i++) {
    const x = sphere[i * 3]
    const y = sphere[i * 3 + 1]
    const z = sphere[i * 3 + 2]
    const { lon, lat } = vec3ToLonLat(x, y, z)

    if (!isLand(lon, lat)) {
      /*
       * 海洋也布点，但极暗。纯靠陆地点的话，球体轮廓会随着自转时隐时现，
       * 看着像"一堆碎片在飘"；铺一层暗点是让球始终是一个完整的球。
       */
      pos.push(x * EARTH_R, y * EARTH_R, z * EARTH_R)
      col.push(ocean.r, ocean.g, ocean.b)
      continue
    }

    const hi = elevation(lon, lat)
    if (hi < topoCut.mid) {
      tmp.copy(jade)
    } else if (hi < topoCut.high) {
      tmp.copy(jade).lerp(gold, (hi - topoCut.mid) / (topoCut.high - topoCut.mid))
    } else {
      tmp.copy(gold).lerp(snow, Math.min(1, (hi - topoCut.high) / 60))
    }

    // 轻微亮度抖动：让点阵有颗粒质感，而不是一整块死色
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    const jitter = 0.86 + (((seed >>> 9) % 100) / 100) * 0.32

    pos.push(x * EARTH_R, y * EARTH_R, z * EARTH_R)
    col.push(tmp.r * jitter, tmp.g * jitter, tmp.b * jitter)
  }

  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  geo.setAttribute('color', new THREE.Float32BufferAttribute(col, 3))

  /*
   * 点尺寸是这套视觉的关键。注意 three 的 gl_PointSize 公式是
   *     size * (画布高/2) / 视空间深度
   * —— 里面**没有 fov 因子**，所以改 fov 只会让球变大、点不变。
   * 想让点在屏幕上约 2.6px（球直径的 0.8%），size 需要 ≈ 0.052。
   * 点只有 0.9px 时整体会糊成一层雾，看着就像"球里什么都没有"。
   */
  const mat = new THREE.PointsMaterial({
    size: 0.032,
    sizeAttenuation: true,
    vertexColors: true,
    transparent: true,
    opacity: 0,
    depthWrite: false,
  })
  const pts = new THREE.Points(geo, mat)
  pts.renderOrder = 2
  return pts
}

/** 经纬网：每 15° 一条，另用稻金单独描出北回归线 */
function buildGraticule(): THREE.Group {
  const group = new THREE.Group()
  const SEG = 120

  const pushMeridian = (lon: number, verts: number[]) => {
    for (let i = 0; i < SEG; i++) {
      const a = lonLatToVec3(lon, -90 + (180 * i) / SEG, GRID_R)
      const b = lonLatToVec3(lon, -90 + (180 * (i + 1)) / SEG, GRID_R)
      verts.push(a.x, a.y, a.z, b.x, b.y, b.z)
    }
  }
  const pushParallel = (lat: number, verts: number[]) => {
    for (let i = 0; i < SEG * 2; i++) {
      const a = lonLatToVec3(-180 + (360 * i) / (SEG * 2), lat, GRID_R)
      const b = lonLatToVec3(-180 + (360 * (i + 1)) / (SEG * 2), lat, GRID_R)
      verts.push(a.x, a.y, a.z, b.x, b.y, b.z)
    }
  }

  const thin: number[] = []
  for (let lon = -180; lon < 180; lon += 15) pushMeridian(lon, thin)
  for (let lat = -75; lat <= 75; lat += 15) pushParallel(lat, thin)

  const g1 = new THREE.BufferGeometry()
  g1.setAttribute('position', new THREE.Float32BufferAttribute(thin, 3))
  const mesh1 = new THREE.LineSegments(
    g1,
    new THREE.LineBasicMaterial({ color: 0x49a377, transparent: true, opacity: 0.12 })
  )
  mesh1.renderOrder = 1
  group.add(mesh1)

  // 北回归线：佛山就在这条线附近，用稻金点出来
  const tropic: number[] = []
  pushParallel(23.44, tropic)
  const g2 = new THREE.BufferGeometry()
  g2.setAttribute('position', new THREE.Float32BufferAttribute(tropic, 3))
  const mesh2 = new THREE.LineSegments(
    g2,
    new THREE.LineBasicMaterial({ color: 0xd8b877, transparent: true, opacity: 0.42 })
  )
  mesh2.renderOrder = 1
  group.add(mesh2)

  return group
}

/** 大气辉光：外缘青绿、内圈偏暖，避开通用的"蓝色大气" */
function buildAtmosphere(): THREE.Mesh {
  const geo = new THREE.SphereGeometry(ATMOS_R, 64, 48)
  const mat = new THREE.ShaderMaterial({
    transparent: true,
    side: THREE.BackSide,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
    vertexShader: `
      varying vec3 vNormal;
      void main() {
        vNormal = normalize(normalMatrix * normal);
        gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
      }`,
    fragmentShader: `
      varying vec3 vNormal;
      void main() {
        float rim = pow(max(0.0, 0.60 - dot(vNormal, vec3(0.0, 0.0, 1.0))), 3.1);
        vec3 jade = vec3(0.26, 0.82, 0.60);
        vec3 warm = vec3(0.80, 0.70, 0.46);
        vec3 col = mix(jade, warm, clamp(rim * 1.4, 0.0, 1.0));
        gl_FragColor = vec4(col, 1.0) * rim * 0.9;
      }`,
  })
  const mesh = new THREE.Mesh(geo, mat)
  mesh.renderOrder = 4
  return mesh
}

/** 目标地标记：一颗金星 + 两圈向外扩散的脉冲环（佛山） */
function buildMarker(): { dot: THREE.Sprite; pulses: THREE.Mesh[] } {
  const dir = lonLatToVec3(HOME_LON, HOME_LAT, 1).normalize()

  const c = document.createElement('canvas')
  c.width = c.height = 64
  const ctx = c.getContext('2d')!
  const grad = ctx.createRadialGradient(32, 32, 0, 32, 32, 32)
  grad.addColorStop(0, 'rgba(255,242,205,1)')
  grad.addColorStop(0.28, 'rgba(240,205,130,0.85)')
  grad.addColorStop(1, 'rgba(216,184,119,0)')
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, 64, 64)

  const sprite = new THREE.Sprite(
    new THREE.SpriteMaterial({
      map: new THREE.CanvasTexture(c),
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    })
  )
  sprite.position.copy(dir).multiplyScalar(EARTH_R * 1.006)
  sprite.scale.setScalar(0.15)
  sprite.renderOrder = 3

  const quat = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir)
  const pulses: THREE.Mesh[] = []
  for (let i = 0; i < 2; i++) {
    const ring = new THREE.Mesh(
      new THREE.RingGeometry(0.86, 1, 48),
      new THREE.MeshBasicMaterial({
        color: 0xe8cd8e,
        transparent: true,
        opacity: 0.6,
        side: THREE.DoubleSide,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      })
    )
    ring.quaternion.copy(quat)
    ring.position.copy(dir).multiplyScalar(EARTH_R * 1.01)
    ring.renderOrder = 3
    ring.userData.phase = i / 2
    pulses.push(ring)
  }
  return { dot: sprite, pulses }
}

/** 平滑阶梯：x 从 [a,b] 映射到 0→1，两端导数为 0、无跳变 */
function smoothstep(a: number, b: number, x: number): number {
  const t = THREE.MathUtils.clamp((x - a) / (b - a), 0, 1)
  return t * t * (3 - 2 * t)
}

/**
 * 破云云壳：包在地球外侧的一层软云团（Points + 程序生成的絮状贴图）。
 * 俯冲时相机会真实地从这层云里穿过去：近处云团在画面边缘放大、掠过，
 * 这是"破开云层"的立体感来源；全屏云雾只负责最后的画面交接。
 */
function buildClouds(): THREE.Points {
  const N = 460
  const sphere = fibonacciSphere(N)
  const pos: number[] = []
  let seed = 20260915
  const rnd = () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return ((seed >>> 8) % 1000) / 1000
  }
  for (let i = 0; i < N; i++) {
    // 半径 1.05~1.11：云层要有厚度，穿越时才有层次，而不是一个薄壳
    const r = 1.05 + rnd() * 0.06
    pos.push(sphere[i * 3] * r, sphere[i * 3 + 1] * r, sphere[i * 3 + 2] * r)
  }
  const geo = new THREE.BufferGeometry()
  geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))

  // 絮状云贴图：多团径向渐变叠出来的"棉花感"，比单渐变更像云
  const c = document.createElement('canvas')
  c.width = c.height = 128
  const ctx = c.getContext('2d')!
  for (let i = 0; i < 8; i++) {
    const x = 30 + rnd() * 68
    const y = 30 + rnd() * 68
    const rr = 20 + rnd() * 34
    const g = ctx.createRadialGradient(x, y, 0, x, y, rr)
    g.addColorStop(0, 'rgba(255,255,255,0.8)')
    g.addColorStop(1, 'rgba(255,255,255,0)')
    ctx.fillStyle = g
    ctx.fillRect(0, 0, 128, 128)
  }
  // 最后扣一层圆形软边遮罩：内部保留絮状纹理，轮廓收敛为干净的软圆
  ctx.globalCompositeOperation = 'destination-in'
  const rim = ctx.createRadialGradient(64, 64, 0, 64, 64, 62)
  rim.addColorStop(0, 'rgba(255,255,255,1)')
  rim.addColorStop(0.55, 'rgba(255,255,255,0.9)')
  rim.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = rim
  ctx.fillRect(0, 0, 128, 128)
  ctx.globalCompositeOperation = 'source-over'

  const mat = new THREE.PointsMaterial({
    size: 0.55,
    map: new THREE.CanvasTexture(c),
    transparent: true,
    opacity: 0,
    depthWrite: false,
    color: 0xf2f7ff,
  })
  const pts = new THREE.Points(geo, mat)
  pts.renderOrder = 5
  return pts
}

/**
 * 拖动旋转 + 惯性。
 * 指针捕获要等到确认拖动（位移 > 3px）之后才设置，否则会把子元素的点击吞掉。
 */
function bindEarthPointer(canvas: HTMLCanvasElement): () => void {
  let lastX = 0
  let lastY = 0
  let lastT = 0
  let captured = false

  const onDown = (e: PointerEvent) => {
    isDragging = true
    dragVelocity = 0
    lastX = e.clientX
    lastY = e.clientY
    lastT = performance.now()
    canvas.style.cursor = 'grabbing'
  }
  const onMove = (e: PointerEvent) => {
    if (!isDragging) return
    const now = performance.now()
    const dx = e.clientX - lastX
    const dy = e.clientY - lastY
    const dt = Math.max(8, now - lastT)
    if (!captured && Math.abs(dx) + Math.abs(dy) > 3) {
      // 合成事件（自动化测试）没有真实指针，捕获会抛 NotFoundError，忽略即可
      try {
        canvas.setPointerCapture(e.pointerId)
        captured = true
      } catch {
        captured = false
      }
    }
    dragOffsetY += dx * 0.0062
    dragOffsetX = THREE.MathUtils.clamp(dragOffsetX + dy * 0.0042, -0.75, 0.75)
    dragVelocity = ((dx * 0.0062) / dt) * 14
    lastX = e.clientX
    lastY = e.clientY
    lastT = now
  }
  const onUp = (e: PointerEvent) => {
    if (!isDragging) return
    isDragging = false
    canvas.style.cursor = 'grab'
    if (captured) {
      try {
        canvas.releasePointerCapture(e.pointerId)
      } catch {
        /* 同上：忽略 */
      }
      captured = false
    }
  }

  canvas.style.cursor = 'grab'
  canvas.addEventListener('pointerdown', onDown)
  canvas.addEventListener('pointermove', onMove)
  canvas.addEventListener('pointerup', onUp)
  canvas.addEventListener('pointercancel', onUp)

  return () => {
    canvas.removeEventListener('pointerdown', onDown)
    canvas.removeEventListener('pointermove', onMove)
    canvas.removeEventListener('pointerup', onUp)
    canvas.removeEventListener('pointercancel', onUp)
  }
}

function initThreeEarth(canvas: HTMLCanvasElement) {
  const W = canvas.clientWidth || 420
  const H = canvas.clientHeight || 420

  threeScene = new THREE.Scene()
  threeCamera = new THREE.PerspectiveCamera(EARTH_FOV, W / H, 0.1, 120)
  camDist = CAM_D0
  threeCamera.position.set(0, 0, camDist)
  threeCamera.lookAt(0, 0, 0)

  threeRenderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true })
  threeRenderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  threeRenderer.setSize(W, H, false)
  threeRenderer.setClearColor(0x000000, 0)

  earthGroup = new THREE.Group()
  threeScene.add(earthGroup)

  // 内层实心球：挡住背面点阵，同时充当"海洋"
  coreMesh = new THREE.Mesh(
    new THREE.SphereGeometry(CORE_R, 64, 48),
    new THREE.MeshBasicMaterial({ color: 0x06170f })
  )
  coreMesh.renderOrder = 0
  earthGroup.add(coreMesh)

  gridLines = buildGraticule()
  earthGroup.add(gridLines)

  landPoints = buildLandPoints()
  earthGroup.add(landPoints)

  const marker = buildMarker()
  markerDot = marker.dot
  markerPulses = marker.pulses
  earthGroup.add(markerDot)
  markerPulses.forEach((p) => earthGroup!.add(p))

  atmosphereMesh = buildAtmosphere()
  earthGroup.add(atmosphereMesh)

  // 云壳挂在场景而不是 earthGroup：不严格跟随自转/拖拽，才有"云在飘"的错层感
  cloudPoints = buildClouds()
  threeScene.add(cloudPoints)

  loadMasks()
  unbindPointer = bindEarthPointer(canvas)

  // 尺寸自适应：观察 canvas 自身即可，它随容器变化
  const ro = new ResizeObserver(() => {
    if (!canvas || !threeCamera || !threeRenderer) return
    const w = canvas.clientWidth
    const h = canvas.clientHeight
    if (!w || !h) return
    threeCamera.aspect = w / h
    threeCamera.updateProjectionMatrix()
    threeRenderer.setSize(w, h, false)
  })
  ro.observe(canvas)
  earthResizeObserver = ro

  clock = new THREE.Clock()
  resetEarthView()
  animate()
}

function animate() {
  animationFrameId = requestAnimationFrame(animate)
  if (!threeScene || !threeCamera || !threeRenderer || !earthGroup || !clock) return

  const dt = Math.min(clock.getDelta(), 0.06)
  const elapsed = clock.elapsedTime

  // ===== 俯冲时间线（破云降落）=====
  // 必须按真实时钟推进：无头/低帧率环境 rAF 会被节流到 ~1fps，逐帧累加会卡在半路。
  if (earthDescending.value) {
    if (!diveT0) {
      diveT0 = performance.now()
      retreatT0 = 0
    }
    descendProgress = Math.min(1, (performance.now() - diveT0) / DIVE_MS)
  } else if (diveT0) {
    // 俯冲中止（无结果/出错）：从当前进度平滑退回，不瞬间跳变
    if (!retreatT0) {
      retreatT0 = performance.now()
      retreatFrom = descendProgress
    }
    const u = Math.min(1, (performance.now() - retreatT0) / RETREAT_MS)
    descendProgress = retreatFrom * Math.pow(1 - u, 2)
    if (u >= 1) {
      diveT0 = 0
      retreatT0 = 0
      retreatFrom = 0
    }
  }
  const p = descendProgress

  // 自转 + 拖动惯性。自转按秒推进，不按帧 —— 否则低帧率环境会明显变慢
  if (!isDragging) {
    spinAngle += 0.096 * dt
    dragOffsetY += dragVelocity
    dragVelocity *= 0.93
    if (Math.abs(dragVelocity) < 0.00003) dragVelocity = 0
  }

  // 朝向：俯冲前段把球体平滑转回佛山正对镜头（要赶在相机推近完成之前对齐）
  const align = smoothstep(0, 0.55, p)
  earthGroup.rotation.y = THREE.MathUtils.lerp(spinAngle + dragOffsetY, 0, align)

  // 相机：先缓后急地砸进云层（CAM_D1 在云壳半径之内）
  if (p > 0.001) {
    camDist = THREE.MathUtils.lerp(CAM_D0, CAM_D1, Math.pow(p, 1.6))
  } else {
    camDist += (CAM_D0 - camDist) * Math.min(1, dt * 4)
  }
  const tilt = THREE.MathUtils.lerp(
    THREE.MathUtils.clamp(dragOffsetX * 0.62, -0.5, 0.5),
    0.16,
    align
  )
  threeCamera.position.set(0, Math.sin(tilt) * camDist, Math.cos(tilt) * camDist)
  threeCamera.lookAt(0, 0, 0)

  // 云壳：接近时浮现；近侧云团越来越大，最后从画面边缘掠过
  if (cloudPoints) {
    ;(cloudPoints.material as THREE.PointsMaterial).opacity = smoothstep(0.2, 0.5, p) * 0.95
    // 云的自转慢半拍、再加一点独立漂移，与地面错开才有"云在飘"的感觉
    cloudPoints.rotation.y = earthGroup.rotation.y * 0.6 + elapsed * 0.012
  }

  // 全屏云雾：相机扎进云壳后接管画面，把「地球 → 城市地图」的切换藏在雾里。
  // 只在俯冲/退回期间（diveT0 活跃）才写 —— 否则会把 backToEarth 的反向穿云
  // 云雾闪现立即覆写掉。
  if (diveT0) {
    const veil = smoothstep(0.62, 0.88, p)
    if (Math.abs(veil - cloudVeilOpacity.value) > 0.008) cloudVeilOpacity.value = veil
  }

  // 点阵淡入：按真实时钟推进，帧率低也不会卡在半透明状态
  if (landPoints) {
    pointsReveal = Math.min(1, (performance.now() - pointsBornAt) / 260)
    ;(landPoints.material as THREE.PointsMaterial).opacity = pointsReveal * 0.94
  }

  // 标记脉冲：两圈错相位向外扩散
  markerPulses.forEach((ring) => {
    const t = (elapsed * 0.52 + (ring.userData.phase as number)) % 1
    ring.scale.setScalar(0.02 + t * 0.085 * (1 + descendProgress * 1.6))
    ;(ring.material as THREE.MeshBasicMaterial).opacity = (1 - t) * 0.62
  })
  if (markerDot) {
    markerDot.scale.setScalar(0.15 * (1 + Math.sin(elapsed * 2.2) * 0.09) * (1 + descendProgress * 0.9))
  }

  // HUD 读数节流更新（不要每帧写响应式）。
  // 注意符号：球体绕 +Y 转过 θ 后，落在屏幕正前方的经度是 HOME_LON − θ。
  if (elapsed - lastHudSync > 0.1) {
    lastHudSync = elapsed
    const facing = HOME_LON - (earthGroup.rotation.y * 180) / Math.PI
    earthFacingLon.value = ((((facing + 180) % 360) + 360) % 360) - 180
  }

  threeRenderer.render(threeScene, threeCamera)
}

function disposeThreeEarth() {
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
    animationFrameId = 0
  }
  if (unbindPointer) {
    unbindPointer()
    unbindPointer = null
  }
  if (earthResizeObserver) {
    earthResizeObserver.disconnect()
    earthResizeObserver = null
  }
  if (threeScene) {
    threeScene.traverse((obj) => {
      const any = obj as any
      if (any.geometry) any.geometry.dispose()
      const m = any.material as THREE.Material | THREE.Material[] | undefined
      if (Array.isArray(m)) m.forEach((mm) => mm.dispose())
      else if (m) m.dispose()
    })
    threeScene.clear()
    threeScene = null
  }
  if (threeRenderer) {
    threeRenderer.dispose()
    threeRenderer = null
  }
  threeCamera = null
  earthGroup = null
  landPoints = null
  gridLines = null
  coreMesh = null
  atmosphereMesh = null
  markerDot = null
  markerPulses = []
  cloudPoints = null
  clock = null
  isDragging = false
  dragVelocity = 0
  descendProgress = 0
  pointsReveal = 0
}
</script>

<template>
  <el-card shadow="hover">
    <template #header>
      <div class="card-header">
        <span>村镇管理</span>
        <el-button v-if="canEditVillage(adminStore.role)" type="primary" @click="openCreate">新增村镇</el-button>
      </div>
    </template>
    <!-- 只读账号看不到任何写入口，说明一句：现在能做什么、要找谁 -->
    <ReadOnlyNote
      v-if="!canEditVillage(adminStore.role)"
      text="当前账号只能查看村镇档案。改动需要超级管理员，或由该村镇的管理员操作。"
    />
    <el-table :data="villages" v-loading="loading" stripe border>
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="code" label="编码" width="100" />
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column prop="mainCrops" label="主要作物" />
      <el-table-column prop="terrain" label="地形" width="100" />
      <el-table-column label="帮扶等级" width="100">
        <template #default="{ row }">
          <el-tag :type="levelType(row.assistanceLevel)" size="small">{{ row.assistanceLevel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="canEditVillage(adminStore.role)" type="primary" text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="canDelete(adminStore.role)" type="danger" text size="small" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 新增/编辑村镇对话框 -->
  <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑村镇' : '新增村镇'" width="600px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="村镇名称">
        <el-input v-model="form.name" placeholder="输入名称，或点右侧按钮从地球地图选择">
          <template #append>
            <el-button @click="openEarth">从地球地图搜索</el-button>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item label="主要作物"><el-input v-model="form.mainCrops" placeholder="如：水稻、蔬菜" /></el-form-item>
      <el-row :gutter="16">
        <el-col :span="12">
          <el-form-item label="地形">
            <el-select v-model="form.terrain" style="width: 100%">
              <el-option label="平原" value="平原" />
              <el-option label="丘陵" value="丘陵" />
              <el-option label="山地" value="山地" />
              <el-option label="盆地" value="盆地" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="帮扶等级">
            <el-select v-model="form.assistanceLevel" style="width: 100%">
              <el-option label="一般" value="一般" />
              <el-option label="重点" value="重点" />
              <el-option label="特级" value="特级" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <!-- ===== 地球地图选址弹窗：深色观测舱 ===== -->
  <el-dialog
    v-model="earthVisible"
    width="760px"
    :close-on-click-modal="false"
    @close="closeEarth"
    align-center
    class="earth-dialog"
  >
    <template #header>
      <div class="eg-head">
        <span class="eg-head-mark"></span>
        <span class="eg-head-title">地球地图选址</span>
        <span class="eg-head-sub">为村镇定位经纬度</span>
      </div>
    </template>

    <div class="earth-stage">
      <!-- 观测状态条：拖动的实时读数在这里（地球不在时无需再报读数） -->
      <div class="eg-hud" v-if="earthResults.length === 0">
        <span class="eg-hud-live"><i></i>{{ earthDescending ? '穿越云层' : '观测中' }}</span>
        <span class="eg-hud-lon">{{ facingLonText }}</span>
        <span class="eg-hud-tip">拖动旋转 · 回车搜索</span>
      </div>

      <!-- 地球（搜索出结果后让位给地图视图；切换藏在云雾最浓处完成） -->
      <div class="earth-wrap" v-if="earthResults.length === 0">
        <canvas ref="earthCanvasRef" class="three-earth-canvas"></canvas>
      </div>

      <!-- 搜索指令栏：悬浮在球体下方，结果视图出现后隐去 -->
      <div class="earth-search" v-if="earthResults.length === 0">
        <input
          class="eg-input"
          v-model="earthKeyword"
          type="text"
          placeholder="输入地点名称，如：白坭镇"
          :disabled="earthSearching"
          @keyup.enter="doEarthSearch"
        />
        <button
          class="eg-search-btn"
          :disabled="earthSearching"
          @click="doEarthSearch"
        >
          <span v-if="earthSearching" class="eg-spin"></span>
          <span>{{ earthSearching ? '降落中' : '搜索' }}</span>
        </button>
      </div>

      <!-- 降落后：静态地图 + 地点列表 -->
      <div class="earth-landed" v-if="earthResults.length > 0 && !earthSearching">
        <!-- 左：地图 -->
        <div class="landed-map">
          <div class="map-box" v-loading="earthMapLoading">
            <img v-if="earthMapUrl" :src="earthMapUrl" alt="地点位置地图" class="map-img" />
            <div v-else class="map-placeholder">地图加载中…</div>
            <span class="map-badge" v-if="selectedPlace">{{ selectedPlace.location }}</span>
          </div>
          <div class="map-caption" v-if="selectedPlace">
            <span class="mc-name">{{ selectedPlace.name }}</span>
            <span class="mc-sub">{{ selectedPlace.district || selectedPlace.city }}</span>
          </div>
          <div class="map-actions">
            <button class="eg-btn ghost" @click="backToEarth">返回地球</button>
            <button class="eg-btn primary" :disabled="!selectedPlace" @click="confirmPlace">
              选定该地点
            </button>
          </div>
        </div>

        <!-- 右：地点列表 -->
        <div class="landed-list">
          <div class="result-hint">
            <span>已降落 · {{ earthResults.length }} 处</span>
            <span class="rh-sub">单击预览 · 双击选定</span>
          </div>
          <div class="result-scroll">
            <div
              v-for="(place, idx) in earthResults"
              :key="idx"
              class="result-item"
              :class="{ active: selectedPlace?.location === place.location }"
              @click="pickPlace(place)"
              @dblclick="pickPlace(place); confirmPlace()"
            >
              <span class="ri-idx">{{ String(idx + 1).padStart(2, '0') }}</span>
              <div class="ri-body">
                <div class="result-name">{{ place.name }}</div>
                <div class="result-addr">
                  {{ place.province }}{{ place.city }}{{ place.district }} · {{ place.address }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 破云云雾：覆盖整个舞台。俯冲时由 three 时间线推上来盖住画面，
           切到城市地图后由 dissipateVeil 上飘散去 -->
      <div
        class="eg-cloudveil"
        :style="{
          opacity: cloudVeilOpacity,
          transform: 'translateY(' + -veilLift * 7 + '%) scale(' + (1 + veilLift * 0.16) + ')',
        }"
      ></div>
    </div>
  </el-dialog>
</template>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }

/* ===== 地球地图选址弹窗：深色观测舱 ===== */
/*
 * 弹窗本体走深色，让地球的深空背景与外壳连成一片，避免深浅割裂。
 */
.earth-stage {
  --eg-jade: #6fd39a;
  --eg-jade-deep: #45a274;
  --eg-gold: #d8b877;
  --eg-mist: #93b3a4;
  --eg-line: rgba(111, 211, 154, 0.16);
  --eg-text: #eaf6f0;
  position: relative; /* 搜索栏/云雾都以它为定位基准 */
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 460px;
}

/* --- 观测状态条 --- */
.eg-hud {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--eg-line);
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--eg-mist);
}
.eg-hud-live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--eg-jade);
}
.eg-hud-live i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--eg-jade);
  animation: eg-ping 2.2s ease-out infinite;
}
.eg-hud-lon {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--eg-gold);
  letter-spacing: 0.02em;
  text-transform: none;
}
.eg-hud-tip {
  margin-left: auto;
  opacity: 0.6;
  letter-spacing: 0.02em;
  text-transform: none;
}

/* --- 地球 --- */
.earth-wrap {
  position: relative;
  flex: 1;
  min-height: 0;
  display: grid;
  place-items: center;
}
/* 球体外围的氛围光。用径向渐变淡出、不做硬边圆 */
.earth-wrap::before {
  content: '';
  position: absolute;
  inset: -4% -16%;
  pointer-events: none;
  background: radial-gradient(
    circle at 50% 50%,
    rgba(26, 96, 68, 0.3) 0%,
    rgba(16, 62, 45, 0.15) 32%,
    rgba(8, 30, 22, 0.05) 50%,
    transparent 66%
  );
}
/*
 * canvas 撑满容器（不做方形约束）—— 透视投影下球体始终是圆的，
 * 球体占画面高度的比例只由 fov 和相机距离决定，与画布尺寸无关，
 * 任何窗口尺寸下球体都不会被容器裁切。
 */
.three-earth-canvas {
  position: relative;
  display: block;
  width: 100%;
  height: 100%;
  /* 不要在这里加 border-radius：圆形裁切会连大气辉光一起裁掉 */
}
/*
 * 破云云雾：覆盖整个舞台（不能只放在地球容器里 —— 结果视图切换时
 * 那个容器会被 v-if 卸掉，云雾也跟着消失，交接就穿帮了）。
 * 透明度/位移都由 JS 驱动：three 时间线负责"涌上来"，dissipateVeil 负责"上飘散去"。
 */
.eg-cloudveil {
  position: absolute;
  inset: 0;
  z-index: 6;
  pointer-events: none;
  opacity: 0;
  will-change: opacity, transform;
  background:
    radial-gradient(
      ellipse 74% 58% at 50% 44%,
      rgba(255, 255, 255, 0.99) 0%,
      rgba(240, 246, 252, 0.97) 46%,
      rgba(216, 228, 242, 0.94) 100%
    ),
    radial-gradient(
      ellipse 130% 80% at 50% 118%,
      rgba(198, 214, 232, 0.92) 0%,
      rgba(198, 214, 232, 0) 62%
    );
}

/*
 * 搜索指令栏悬浮在球体下方，把竖向空间让给地球。
 * 深色玻璃：它压在球体的云雾辉光上，浅底会变成一块"补丁"。
 */
.earth-search {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  width: min(430px, 88%);
  z-index: 4;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 8px 8px 16px;
  border-radius: 14px;
  border: 1px solid rgba(111, 211, 154, 0.22);
  background: rgba(7, 26, 20, 0.58);
  backdrop-filter: blur(14px) saturate(150%);
  box-shadow: 0 18px 44px -20px rgba(0, 0, 0, 0.85);
  transition: border-color 0.24s ease, box-shadow 0.24s ease, background 0.24s ease;
}
.earth-search:focus-within {
  border-color: rgba(111, 211, 154, 0.52);
  background: rgba(10, 34, 26, 0.72);
  box-shadow: 0 0 0 4px rgba(84, 189, 136, 0.12), 0 18px 44px -20px rgba(0, 0, 0, 0.85);
}
.eg-input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: var(--eg-text);
  font-family: inherit;
  font-size: 14px;
  letter-spacing: 0.01em;
}
.eg-input::placeholder {
  color: rgba(147, 179, 164, 0.62);
}
.eg-search-btn {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--eg-jade), var(--eg-jade-deep));
  color: #04231a;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.05em;
  cursor: pointer;
  box-shadow: 0 6px 18px -9px rgba(84, 189, 136, 0.95);
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1), filter 0.2s ease;
}
.eg-search-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.07);
}
.eg-search-btn:active:not(:disabled) {
  transform: translateY(0) scale(0.985);
}
.eg-search-btn:disabled {
  cursor: progress;
  opacity: 0.72;
}
.eg-spin {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid rgba(4, 35, 26, 0.28);
  border-top-color: #04231a;
  animation: eg-rotate 0.7s linear infinite;
}

/* --- 降落后：地图 + 列表 --- */
.earth-landed {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  animation: results-fade-in 0.46s cubic-bezier(0.22, 1, 0.36, 1) both;
}
.landed-map {
  flex: 0 0 372px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.map-box {
  position: relative;
  width: 100%;
  height: 244px;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid rgba(111, 211, 154, 0.2);
  background: #0a1d16;
  box-shadow: 0 20px 44px -26px rgba(0, 0, 0, 0.95);
}
.map-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.map-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--eg-mist);
}
.map-badge {
  position: absolute;
  left: 10px;
  bottom: 10px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(111, 211, 154, 0.26);
  background: rgba(4, 20, 14, 0.76);
  color: var(--eg-gold);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10.5px;
  letter-spacing: 0.03em;
  backdrop-filter: blur(6px);
}
.map-caption {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-height: 20px;
}
.mc-name {
  font-size: 15px;
  font-weight: 650;
  color: var(--eg-text);
  letter-spacing: 0.01em;
}
.mc-sub {
  font-size: 11.5px;
  color: var(--eg-mist);
}
.map-actions {
  display: flex;
  gap: 8px;
  margin-top: auto;
}
.eg-btn {
  height: 38px;
  border-radius: 10px;
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.03em;
  cursor: pointer;
  transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1), border-color 0.2s ease,
    background 0.2s ease, color 0.2s ease, filter 0.2s ease;
}
.eg-btn.ghost {
  flex: 0 0 auto;
  padding: 0 16px;
  border: 1px solid var(--eg-line);
  background: transparent;
  color: var(--eg-mist);
}
.eg-btn.ghost:hover {
  color: var(--eg-text);
  border-color: rgba(111, 211, 154, 0.4);
  background: rgba(255, 255, 255, 0.04);
}
.eg-btn.primary {
  flex: 1;
  border: none;
  background: linear-gradient(135deg, var(--eg-jade), var(--eg-jade-deep));
  color: #04231a;
  box-shadow: 0 6px 18px -9px rgba(84, 189, 136, 0.95);
}
.eg-btn.primary:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.07);
}
.eg-btn.primary:disabled {
  background: rgba(255, 255, 255, 0.07);
  color: rgba(147, 179, 164, 0.5);
  box-shadow: none;
  cursor: not-allowed;
}

/* --- 结果列表 --- */
.landed-list {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.result-hint {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 10px;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--eg-mist);
}
.rh-sub {
  font-size: 10.5px;
  letter-spacing: 0.02em;
  text-transform: none;
  opacity: 0.6;
}
.result-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}
.result-scroll::-webkit-scrollbar {
  width: 6px;
}
.result-scroll::-webkit-scrollbar-thumb {
  border-radius: 3px;
  background: rgba(111, 211, 154, 0.22);
}
.result-item {
  position: relative;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 8px;
  padding: 12px 12px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  border-radius: 11px;
  background: rgba(255, 255, 255, 0.035);
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease,
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}
/* 选中态用左侧生长出来的色条表达，比整块变色克制 */
.result-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 2px;
  height: 0;
  border-radius: 2px;
  background: var(--eg-jade);
  transform: translateY(-50%);
  transition: height 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}
.result-item:hover {
  border-color: rgba(111, 211, 154, 0.32);
  background: rgba(84, 189, 136, 0.07);
  transform: translateX(2px);
}
.result-item.active {
  border-color: rgba(111, 211, 154, 0.5);
  background: rgba(84, 189, 136, 0.12);
}
.result-item.active::before {
  height: 62%;
}
.ri-idx {
  flex: 0 0 auto;
  padding-top: 2px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: var(--eg-gold);
  opacity: 0.82;
}
.ri-body {
  flex: 1;
  min-width: 0;
}
.result-name {
  margin-bottom: 4px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--eg-text);
}
.result-addr {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--eg-mist);
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

/* --- 动画 --- */
@keyframes eg-ping {
  0% { box-shadow: 0 0 0 0 rgba(111, 211, 154, 0.55); }
  70% { box-shadow: 0 0 0 7px rgba(111, 211, 154, 0); }
  100% { box-shadow: 0 0 0 0 rgba(111, 211, 154, 0); }
}
@keyframes eg-rotate {
  to { transform: rotate(360deg); }
}
/* 结果视图从云雾里"沉降"浮现：轻微下落 + 缩放回落 */
@keyframes results-fade-in {
  from { opacity: 0; transform: translateY(-10px) scale(1.045); }
  to { opacity: 1; transform: none; }
}
</style>

<!--
  弹窗外壳（遮罩 / 圆角 / 内边距）不在组件作用域内，需要单独的全局块。
  全部规则都以 .earth-dialog 作前缀限定，不会外溢到其他弹窗。
-->
<style>
.earth-dialog.el-dialog {
  overflow: hidden;
  border-radius: 20px;
  border: 1px solid rgba(111, 211, 154, 0.16);
  background: linear-gradient(158deg, #08201a 0%, #04120c 58%, #071c15 100%);
  box-shadow: 0 44px 96px -32px rgba(0, 0, 0, 0.78), 0 0 90px -40px rgba(40, 160, 110, 0.5);
}
.earth-dialog .el-dialog__header {
  margin: 0;
  padding: 18px 24px 16px;
  border-bottom: 1px solid rgba(111, 211, 154, 0.12);
}
.earth-dialog .el-dialog__body {
  padding: 16px 24px 20px;
}
.earth-dialog .el-dialog__headerbtn {
  top: 14px;
  right: 14px;
}
.earth-dialog .el-dialog__headerbtn .el-dialog__close {
  color: rgba(147, 179, 164, 0.78);
  transition: color 0.2s ease;
}
.earth-dialog .el-dialog__headerbtn:hover .el-dialog__close {
  color: #6fd39a;
}
/* 遮罩再压深一档，突出观测舱的沉浸感；:has 不被支持时自动沿用全局遮罩样式 */
.el-overlay:has(.earth-dialog) {
  background: rgba(3, 10, 7, 0.68);
}
</style>


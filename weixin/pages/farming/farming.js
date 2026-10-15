const { request, BASE_URL } = require('../../utils/request')
const { loadCropAreas } = require('../../utils/user-crops')
const app = getApp()

// 作物本地图片映射表 - 使用小程序本地images/crops目录下的图片
const CROP_LOCAL_IMAGES = {
  '丝苗米': '/images/crops/rice-simiaomi.jpg',
  '早稻': '/images/crops/rice-early.jpg',
  '晚稻': '/images/crops/rice-late.jpg',
  '黑皮冬瓜': '/images/crops/winter-melon.jpg',
  '菜心': '/images/crops/choy-sum.jpg',
  '生菜': '/images/crops/lettuce.jpg',
  '粉葛': '/images/crops/kudzu.jpg',
  '单丛茶': '/images/crops/dancong-tea.jpg',
  '茶叶': '/images/crops/dancong-tea.jpg',
  '荔枝': '/images/crops/lychee.jpg',
  '百合花': '/images/crops/lily.jpg',
  '蝴蝶兰': '/images/crops/phalaenopsis.jpg',
  '兰花': '/images/crops/orchid.jpg',
  '年桔': '/images/crops/new-year-orange.jpg',
  '莲藕': '/images/crops/lotus-root.jpg',
  '桂花鱼': '/images/crops/mandarin-fish.jpg'
}

// 作物提醒图标映射（emoji）
const CROP_REMINDER_ICONS = {
  '丝苗米': '🌾', '早稻': '🌾', '晚稻': '🌾', '水稻': '🌾',
  '黑皮冬瓜': '🥒', '冬瓜': '🥒',
  '菜心': '🥬', '生菜': '🥬', '蔬菜': '🥬',
  '粉葛': '🥔', '葛根': '🥔',
  '单丛茶': '🍵', '茶叶': '🍵', '茶': '🍵',
  '荔枝': '🍒', '水果': '🍎',
  '百合花': '🌸', '蝴蝶兰': '🌺', '兰花': '🌷', '花卉': '🌷',
  '年桔': '🍊', '柑橘': '🍊',
  '莲藕': '🪷', '桂花鱼': '🐟', '玉米': '🌽'
}

// 作物提醒图标背景色映射
const CROP_ICON_BG = {
  '粮食': 'bg-rice', '水稻': 'bg-rice', '玉米': 'bg-corn',
  '蔬菜': 'bg-veggie',
  '水果': 'bg-fruit',
  '经济作物': 'bg-cash',
  '茶叶': 'bg-tea', '花卉': 'bg-flower', '水产': 'bg-fish'
}

// 获取作物图片URL - 优先使用本地图片，加载更快更稳定
function getCrop3DImageUrl(cropName) {
  return CROP_LOCAL_IMAGES[cropName] || '/images/crops/rice-simiaomi.jpg'
}

// 处理后端返回的图片URL（兼容旧数据和相对路径）
function resolveImageUrl(url, cropName) {
  if (!url) return getCrop3DImageUrl(cropName)
  // 如果是/crops/开头的相对路径，转为完整URL
  if (url.startsWith('/crops/')) {
    // 优先使用小程序本地图片
    const localImg = CROP_LOCAL_IMAGES[cropName]
    if (localImg) return localImg
    return BASE_URL + url
  }
  return url
}

function getDefaultCropImage() {
  return '/images/crops/rice-simiaomi.jpg'
}

/**
 * 把请求失败的原因整理成一句人话。
 * wx.request 的 fail 回调给的是 { errMsg }，HTTP 非 2xx 给的是 { statusCode, data.message }，
 * 两种形态都要能读出来，否则错误条上永远只有一句「加载失败」。
 */
function errText(e) {
  if (!e) return '网络异常，请稍后重试'
  if (e.errMsg) return '网络异常：' + e.errMsg
  if (e.data && e.data.message) return e.data.message
  if (e.message) return e.message
  return '网络异常，请稍后重试'
}

/** 2026-09-18 → 9月18日；解析失败原样返回，不让非法日期把页面打崩 */
function prettyDate(s) {
  if (!s) return ''
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return m ? `${Number(m[2])}月${Number(m[3])}日` : String(s)
}

Page({
  data: {
    // 默认落在「今日任务」（tab 4，分段控件里排第一）
    activeTab: 4,
    // scroll-view 的 scroll-into-view 靠 id 定位，必须与 activeTab 同步更新，
    // 否则切到第 5 个之后的 Tab 时它停在屏幕外，用户以为没切成功
    activeTabId: 'tab-4',
    villageCode: 'baini',
    villageName: '白坭镇',
    defaultCropImage: getDefaultCropImage(),

    // Tab 1: 农事建议
    farmAdvices: [],
    adviceLoading: true,

    // Tab 2: 作物档案
    cropProfiles: [],
    cropLoading: true,

    // Tab 3: 灾害记录
    disasterRecords: [],
    disasterLoading: true,

    // Tab 4: 作物提醒（作物生长引擎生成）
    cropReminders: [],
    reminderLoading: true,

    // Tab 5: 今日任务（根据播种日期自动计算）
    todayTasks: [],
    todayLoading: true,
    isLoggedIn: false,

    // Tab 6: 农事档案（闭环的「记录」环节：提醒 → 执行 → 记录）
    archive: null,
    archiveLoading: true,
    archiveDays: 30,

    // 当前 tab 的加载失败状态。
    // 以前每个 loader 的 catch 里只把 loading 置 false，页面就剩一片空白 ——
    // 真机上一断网/电脑 IP 变了，看起来像程序坏了。现在统一落到这两个字段上，
    // 由 <load-error> 组件给出「重新加载」入口。
    loadError: false,
    loadErrorMsg: ''
  },

  onLoad() {
    const villageCode = app.globalData.villageCode || 'baini'
    const villageName = app.globalData.villageName || '白坭镇'
    // 关键：登录态必须在拉数据之前就绪。
    // 今日任务(loadTodayTasks)一来就判断 isLoggedIn，若沿用 data 初始值 false，
    // 会导致首次进入永远拿到空数据，必须手动切走再切回才有值。
    this.setData({
      villageCode,
      villageName,
      isLoggedIn: !!wx.getStorageSync('token')
    })
    this.loadTabData()
    // onShow 会紧随 onLoad 触发一次，已加载过就别再发一次请求
    this._skipNextShowReload = true
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 1 })
    }
    // 更新登录状态：登录态发生变化时（如在"我的"页登录后返回）需要补加载当前 tab
    const token = wx.getStorageSync('token')
    const wasLoggedIn = this.data.isLoggedIn
    const isLoggedIn = !!token
    this.setData({ isLoggedIn })

    var newCode = app.globalData.villageCode || 'baini'
    var newName = app.globalData.villageName || '白坭镇'
    if (this.data.villageCode !== newCode) {
      this.setData({ villageCode: newCode, villageName: newName })
      this.loadTabData()
      return
    }
    if (isLoggedIn !== wasLoggedIn) {
      this.loadTabData()
      return
    }
    // 从"我的作物"设置完播种日期返回时刷新今日任务
    // 首屏刚由 onLoad 加载过，跳过这一次（onShow 紧随 onLoad 触发）
    if (this._skipNextShowReload) {
      this._skipNextShowReload = false
    } else if (this.data.activeTab === 4) {
      this.loadTodayTasks()
    } else if (this.data.activeTab === 5) {
      this.loadArchive()
    }
  },

  onPullDownRefresh() {
    this.loadTabData().then(() => wx.stopPullDownRefresh())
  },

  switchTab(e) {
    const tab = Number(e.currentTarget.dataset.tab)
    if (tab === this.data.activeTab) return
    this.setData({
      activeTab: tab,
      activeTabId: 'tab-' + tab,
    })
    this.loadTabData()
  },

  // 点错误条上的「重新加载」：重跑当前 tab 的加载逻辑
  onRetryLoad() {
    this.loadTabData()
  },

  loadTabData() {
    // 每次进入都先清掉上一次的失败态，避免成功后错误条还挂着
    this.setData({ loadError: false, loadErrorMsg: '' })
    switch (this.data.activeTab) {
      case 0: return this.loadFarmAdvices()
      case 1: return this.loadCropProfiles()
      case 2: return this.loadDisasterRecords()
      case 3: return this.loadCropReminders()
      case 4: return this.loadTodayTasks()
      case 5: return this.loadArchive()
      default: return Promise.resolve()
    }
  },

  // ==================== Tab 1: 农事建议 ====================
  loadFarmAdvices() {
    this.setData({ adviceLoading: true })
    return request({
      url: '/api/farm/advices',
      data: { villageCode: this.data.villageCode }
    }).then(res => {
      if (res.success && res.data) {
        const advices = res.data.map(item => ({
          ...item,
          icon: this.getAdviceIcon(item.iconKey),
          priorityText: this.getPriorityText(item.priority),
          priorityClass: this.getPriorityClass(item.priority),
          timeAgo: this.formatTimeAgo(item.createTime)
        }))
        this.setData({ farmAdvices: advices, adviceLoading: false })
      } else {
        this.setData({ farmAdvices: [], adviceLoading: false })
      }
    }).catch((e) => {
      this.setData({ adviceLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  getPriorityText(priority) {
    const map = { high: '紧急', medium: '重要', low: '一般' }
    return map[priority] || '一般'
  },

  getAdviceIcon(iconKey) {
    const map = {
      'planting': '🌱',
      'irrigation': '💧',
      'pest': '🐛',
      'harvest': '🌾',
      'fertilize': '🧪',
      'prune': '✂️',
      'sow': '🫘',
      'weed': '🌿',
      'Sprout': '🌱',
      'Watermelon': '💧',
      'Bug': '🐛',
      'Box': '📦',
      'Sun': '☀️',
      'Cloud': '☁️',
      'Rainy': '🌧️',
      'Wind': '💨',
      'Wheat': '🌾',
      'Corn': '🌽',
      'Vegetable': '🥬',
      'Fruit': '🍎',
      'Flower': '🌸',
      'Leaf': '🍃',
      'Warning': '⚠️',
      'Bell': '🔔',
      'Info': 'ℹ️',
      'QuestionFilled': '❓',
      'Setting': '⚙️',
      'Tools': '🔧'
    }
    return map[iconKey] || '🌱'
  },

  getPriorityClass(priority) {
    const map = { high: 'priority-high', medium: 'priority-medium', low: 'priority-low' }
    return map[priority] || 'priority-low'
  },

  // ==================== Tab 2: 作物档案 ====================
  loadCropProfiles() {
    this.setData({ cropLoading: true })
    // 作物档案（村镇总体）+ 当前用户个人种植面积（只有本人的），并行拉取。
    // 展示只认个人面积，不用档案里的 plantingArea。
    return Promise.all([
      request({
        url: '/api/farm/crops',
        data: { villageCode: this.data.villageCode }
      }),
      loadCropAreas()
    ]).then(([res, cropAreas]) => {
      if (res.success && res.data) {
        const profiles = res.data.map((item, index) => {
          const areaVal = cropAreas[item.cropName]
          return {
            ...item,
            riskLevelText: this.getRiskText(item.riskLevel),
            riskClass: this.getRiskClass(item.riskLevel),
            growthStageText: item.growthStage || '生长期',
            cropGradient: this.getCropGradient(item.cropName),
            imageUrl: getCrop3DImageUrl(item.cropName),
            touched: false,
            // 个人种植面积，为空表示尚未在「我的作物」里填写
            myArea: areaVal !== undefined && areaVal !== null ? String(areaVal) : '',
            _index: index
          }
        })
        this.setData({ cropProfiles: profiles, cropLoading: false })
      } else {
        this.setData({ cropProfiles: [], cropLoading: false })
      }
    }).catch((e) => {
      this.setData({ cropProfiles: [], cropLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  onCropTouchStart(e) {
    const index = e.currentTarget.dataset.index
    const key = `cropProfiles[${index}].touched`
    this.setData({ [key]: true })
  },

  onCropTouchEnd(e) {
    const index = e.currentTarget.dataset.index
    const key = `cropProfiles[${index}].touched`
    this.setData({ [key]: false })
  },

  onCropImageError(e) {
    const index = e.currentTarget.dataset.index
    const cropName = this.data.cropProfiles[index].cropName
    const emojiMap = {
      '丝苗米': '🌾', '早稻': '🌾', '晚稻': '🌾',
      '黑皮冬瓜': '🥒', '菜心': '🥬', '生菜': '🥬',
      '粉葛': '🥔', '单丛茶': '🍵', '茶叶': '🍵',
      '荔枝': '🍒', '百合花': '🌸', '蝴蝶兰': '🌺', '兰花': '🌷',
      '年桔': '🍊', '莲藕': '🪷', '桂花鱼': '🐟'
    }
    const emoji = emojiMap[cropName] || '🌱'
    const key = `cropProfiles[${index}].imageLoadFail`
    this.setData({ [key]: emoji })
  },

  getCropGradient(name) {
    const map = {
      '丝苗米': 'gradient-rice',
      '早稻': 'gradient-rice',
      '晚稻': 'gradient-rice',
      '黑皮冬瓜': 'gradient-melon',
      '菜心': 'gradient-veggie',
      '生菜': 'gradient-veggie',
      '粉葛': 'gradient-root',
      '单丛茶': 'gradient-tea',
      '茶叶': 'gradient-tea',
      '荔枝': 'gradient-fruit',
      '百合花': 'gradient-flower',
      '蝴蝶兰': 'gradient-flower',
      '兰花': 'gradient-flower',
      '年桔': 'gradient-citrus',
      '莲藕': 'gradient-lotus',
      '桂花鱼': 'gradient-fish'
    }
    return map[name] || 'gradient-default'
  },

  getRiskText(level) {
    const map = { high: '高风险', medium: '中风险', low: '低风险' }
    return map[level] || '低风险'
  },

  getRiskClass(level) {
    const map = { high: 'risk-high', medium: 'risk-medium', low: 'risk-low' }
    return map[level] || 'risk-low'
  },

  openCropDetail(e) {
    const id = e.currentTarget.dataset.id
    wx.showToast({ title: '作物详情开发中', icon: 'none' })
  },

  // ==================== Tab 3: 灾害记录 ====================
  loadDisasterRecords() {
    this.setData({ disasterLoading: true })
    return request({
      url: '/api/farm/disasters',
      data: { villageCode: this.data.villageCode }
    }).then(res => {
      if (res.success && res.data) {
        const records = res.data.map(item => ({
          ...item,
          severityText: this.getSeverityText(item.severity),
          severityClass: this.getSeverityClass(item.severity),
          timeAgo: this.formatTimeAgo(item.occurTime || item.createTime)
        }))
        this.setData({ disasterRecords: records, disasterLoading: false })
      } else {
        this.setData({ disasterRecords: [], disasterLoading: false })
      }
    }).catch((e) => {
      this.setData({ disasterRecords: [], disasterLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  getSeverityText(severity) {
    const map = { severe: '严重', moderate: '中等', mild: '轻微' }
    return map[severity] || '轻微'
  },

  getSeverityClass(severity) {
    const map = { severe: 'severity-severe', moderate: 'severity-moderate', mild: 'severity-mild' }
    return map[severity] || 'severity-mild'
  },

  // ==================== Tab 4: 作物提醒（作物生长引擎） ====================
  loadCropReminders() {
    this.setData({ reminderLoading: true })
    return request({
      url: '/api/crop/reminders',
      data: { villageCode: this.data.villageCode }
    }).then(res => {
      if (res.success && res.data) {
        const reminders = res.data.map(item => ({
          ...item,
          cropIcon: this.getCropReminderIcon(item.cropName),
          iconBgClass: this.getCropIconBg(item.cropType, item.cropName),
          urgencyClass: this.getUrgencyClass(item.urgency)
        }))
        this.setData({ cropReminders: reminders, reminderLoading: false })
      } else {
        this.setData({ cropReminders: [], reminderLoading: false })
      }
    }).catch((e) => {
      this.setData({ cropReminders: [], reminderLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  getCropReminderIcon(cropName) {
    if (!cropName) return '🌱'
    // 精确匹配
    if (CROP_REMINDER_ICONS[cropName]) return CROP_REMINDER_ICONS[cropName]
    // 模糊匹配
    for (const key in CROP_REMINDER_ICONS) {
      if (cropName.includes(key) || key.includes(cropName)) {
        return CROP_REMINDER_ICONS[key]
      }
    }
    return '🌱'
  },

  getCropIconBg(cropType, cropName) {
    if (cropType && CROP_ICON_BG[cropType]) return CROP_ICON_BG[cropType]
    // 根据作物名称推断
    if (cropName) {
      if (cropName.includes('稻') || cropName.includes('米')) return 'bg-rice'
      if (cropName.includes('玉米')) return 'bg-corn'
      if (cropName.includes('茶')) return 'bg-tea'
      if (cropName.includes('兰') || cropName.includes('花') || cropName.includes('桔')) return 'bg-flower'
      if (cropName.includes('荔枝') || cropName.includes('果')) return 'bg-fruit'
      if (cropName.includes('鱼') || cropName.includes('水产')) return 'bg-fish'
      if (cropName.includes('菜') || cropName.includes('瓜') || cropName.includes('葛') || cropName.includes('藕')) return 'bg-veggie'
    }
    return 'bg-default'
  },

  getUrgencyClass(urgency) {
    const map = { '紧急': 'urgency-urgent', '重要': 'urgency-important', '一般': 'urgency-normal' }
    return map[urgency] || 'urgency-normal'
  },

  // ==================== Helpers ====================
  formatTimeAgo(dateStr) {
    if (!dateStr) return ''
    const now = new Date().getTime()
    const date = new Date(dateStr).getTime()
    if (isNaN(date)) return ''
    const diff = now - date
    const minute = 60 * 1000
    const hour = 60 * minute
    const day = 24 * hour
    if (diff < minute) return '刚刚'
    if (diff < hour) return Math.floor(diff / minute) + '分钟前'
    if (diff < day) return Math.floor(diff / hour) + '小时前'
    if (diff < 7 * day) return Math.floor(diff / day) + '天前'
    const d = new Date(dateStr)
    return (d.getMonth() + 1) + '/' + d.getDate()
  },

  // ==================== Tab 5: 今日任务 ====================
  loadTodayTasks() {
    this.setData({ todayLoading: true })
    if (!this.data.isLoggedIn) {
      this.setData({ todayTasks: [], todayLoading: false })
      return Promise.resolve()
    }
    return request({
      url: '/api/wechat/today-tasks'
    }).then(res => {
      if (res.success && res.data) {
        const tasks = res.data.map(item => ({
          ...item,
          // 后端懒物化后带 recordId/status/completedAt，done 供打卡按钮与样式用
          done: item.status === 'completed',
          completedAt: this.formatClockTime(item.completedAt),
          priorityText: this.getPriorityText(item.priority),
          priorityClass: this.getTaskPriorityClass(item.priority)
        }))
        this.setData({ todayTasks: tasks, todayLoading: false })
      } else {
        this.setData({ todayTasks: [], todayLoading: false })
      }
    }).catch((e) => {
      this.setData({ todayTasks: [], todayLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  // ==================== Tab 6: 农事档案 ====================
  // 闭环的最后一环：今日任务打完卡，历史都留在这里（完成率 / 连续天数 / 日历 / 明细）。
  loadArchive() {
    this.setData({ archiveLoading: true, loadError: false, loadErrorMsg: '' })
    if (!this.data.isLoggedIn) {
      this.setData({ archive: null, archiveLoading: false })
      return Promise.resolve()
    }
    return request({
      url: '/api/wechat/farming-records',
      data: { days: this.data.archiveDays }
    }).then(res => {
      if (res.success && res.data) {
        const data = res.data
        const summary = data.summary || {}
        this.setData({
          archive: {
            days: data.days,
            summary: {
              ...summary,
              // 完成率为 null 表示「还没有任何记录」，页面据此区分 0% 和「未开始」
              rateText: summary.rate === null || summary.rate === undefined ? '--' : summary.rate + '%'
            },
            // 日历格：rate 为 null 的日子渲染成空心格（没打开过小程序的日子不造假数据）
            calendar: (data.calendar || []).map(c => ({
              ...c,
              dateText: prettyDate(c.date),
              heat: c.rate === null ? 'none'
                : c.rate >= 100 ? 'full'
                  : c.rate > 0 ? 'part'
                    : 'zero'
            })),
            groups: (data.groups || []).map(g => ({
              ...g,
              dateText: prettyDate(g.date),
              items: (g.items || []).map(it => ({
                ...it,
                done: it.status === 'completed',
                completedText: this.formatClockTime(it.completedAt),
                priorityText: this.getPriorityText(it.priority),
                priorityClass: this.getTaskPriorityClass(it.priority)
              }))
            }))
          },
          archiveLoading: false
        })
      } else {
        this.setData({ archive: null, archiveLoading: false })
      }
    }).catch((e) => {
      this.setData({ archiveLoading: false, loadError: true, loadErrorMsg: errText(e) })
    })
  },

  // 打卡 / 取消打卡：乐观更新本地状态，失败则回滚并提示
  toggleTaskDone(e) {
    const index = Number(e.currentTarget.dataset.index)
    const task = this.data.todayTasks[index]
    if (!task || !task.recordId) {
      wx.showToast({ title: '任务记录未就绪', icon: 'none' })
      return
    }
    const undo = !task.done
    const key = `todayTasks[${index}].done`
    this.setData({ [key]: undo })
    return request({
      url: `/api/wechat/farming-tasks/${task.recordId}/${undo ? 'complete' : 'uncomplete'}`,
      method: 'PUT'
    }).then(res => {
      if (res.success) {
        wx.showToast({ title: undo ? '已完成打卡' : '已取消打卡', icon: 'none' })
      } else {
        this.setData({ [key]: !undo })
        wx.showToast({ title: res.message || '操作失败', icon: 'none' })
      }
    }).catch(() => {
      this.setData({ [key]: !undo })
      wx.showToast({ title: '网络异常，请重试', icon: 'none' })
    })
  },

  getTaskPriorityClass(priority) {
    const map = { high: 'priority-high', medium: 'priority-medium', low: 'priority-low' }
    return map[priority] || 'priority-low'
  },

  // 把完整时间戳截成 HH:mm（打卡时间展示用），非法值原样返回
  formatClockTime(dateStr) {
    if (!dateStr) return ''
    const m = String(dateStr).match(/(\d{2}):(\d{2})(?::\d{2})?$/)
    return m ? `${m[1]}:${m[2]}` : String(dateStr)
  },

  // 跳转到我的作物页面设置播种日期
  goToMyCrops() {
    wx.navigateTo({ url: '/pages/my-crops/my-crops' })
  }
})

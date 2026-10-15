const { request } = require('../../utils/request')
const { loadCropAreas } = require('../../utils/user-crops')
const app = getApp()

// 作物 emoji 映射
const CROP_ICONS = {
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

// 作物图标背景色映射
const CROP_ICON_BG = {
  '粮食': 'bg-rice', '水稻': 'bg-rice', '玉米': 'bg-corn',
  '蔬菜': 'bg-veggie',
  '水果': 'bg-fruit',
  '经济作物': 'bg-cash',
  '茶叶': 'bg-tea', '花卉': 'bg-flower', '水产': 'bg-fish'
}

Page({
  data: {
    cropList: [],
    selectedCount: 0,
    loading: true,
    saving: false,
    // 加载失败态：作物档案拉不出来时页面会是一片空白，
    // 交给 <load-error> 组件给出「重新加载」入口
    loadError: false,
    loadErrorMsg: ''
  },

  onLoad() {
    this.loadData()
  },

  // 点错误条上的「重新加载」
  onRetryLoad() {
    this.loadData()
  },

  async loadData() {
    this.setData({ loading: true, loadError: false, loadErrorMsg: '' })
    try {
      const villageCode = app.globalData.villageCode || 'baini'

      // 1. 加载本村作物档案
      const cropsRes = await request({
        url: '/api/farm/crops',
        data: { villageCode }
      })

      // 2. 加载用户已选作物
      let myCrops = []
      try {
        const myRes = await request({ url: '/api/wechat/my-crops' })
        if (myRes.success && myRes.data) {
          myCrops = myRes.data
        }
      } catch (e) {
        // 未登录或获取失败，忽略
      }

      // 3. 加载用户播种日期
      let plantingDates = {}
      try {
        const dateRes = await request({ url: '/api/wechat/crop-planting-dates' })
        if (dateRes.success && dateRes.data) {
          plantingDates = dateRes.data
        }
      } catch (e) {
        // 忽略
      }

      // 4. 加载用户个人种植面积（user_crop 表，仅本人数据，不显示村镇总体面积）
      const cropAreas = await loadCropAreas()

      // 5. 合并数据
      const cropList = (cropsRes.success && cropsRes.data ? cropsRes.data : []).map(item => {
        const selected = myCrops.includes(item.cropName)
        const plantingDate = plantingDates[item.cropName] || ''
        const areaVal = cropAreas[item.cropName]
        return {
          ...item,
          cropIcon: this.getCropIcon(item.cropName),
          iconBgClass: this.getCropIconBg(item.cropType, item.cropName),
          selected,
          plantingDate,
          daysPlanted: plantingDate ? this.calcDaysPlanted(plantingDate) : 0,
          // 个人种植面积：为空表示尚未填写（不再回退显示总体档案的 plantingArea）
          myArea: areaVal !== undefined && areaVal !== null ? String(areaVal) : ''
        }
      })

      const selectedCount = cropList.filter(c => c.selected).length
      this.setData({ cropList, selectedCount, loading: false })
    } catch (e) {
      console.error('加载作物列表失败:', e)
      // 作物档案是本页的主数据，拿不到就等于整页不可用 —— 必须用页面级错误条 + 重试，
      // 光靠一闪而过的 toast 用户根本不知道该干什么
      this.setData({
        loading: false,
        loadError: true,
        loadErrorMsg: (e && e.errMsg) || (e && e.data && e.data.message) || (e && e.message) || ''
      })
    }
  },

  getCropIcon(cropName) {
    if (!cropName) return '🌱'
    if (CROP_ICONS[cropName]) return CROP_ICONS[cropName]
    for (const key in CROP_ICONS) {
      if (cropName.includes(key) || key.includes(cropName)) {
        return CROP_ICONS[key]
      }
    }
    return '🌱'
  },

  getCropIconBg(cropType, cropName) {
    if (cropType && CROP_ICON_BG[cropType]) return CROP_ICON_BG[cropType]
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

  calcDaysPlanted(plantingDate) {
    if (!plantingDate) return 0
    const today = new Date()
    const plant = new Date(plantingDate)
    const diff = Math.floor((today - plant) / (1000 * 60 * 60 * 24))
    return diff >= 0 ? diff : 0
  },

  toggleCrop(e) {
    const cropName = e.currentTarget.dataset.name
    const cropList = this.data.cropList.map(item => {
      if (item.cropName === cropName) {
        return { ...item, selected: !item.selected }
      }
      return item
    })
    const selectedCount = cropList.filter(c => c.selected).length
    this.setData({ cropList, selectedCount })
  },

  onDateChange(e) {
    const index = e.currentTarget.dataset.index
    const date = e.detail.value
    const key = `cropList[${index}].plantingDate`
    const daysKey = `cropList[${index}].daysPlanted`
    this.setData({
      [key]: date,
      [daysKey]: this.calcDaysPlanted(date)
    })
  },

  // 输入个人种植面积（亩）
  onAreaInput(e) {
    const index = e.currentTarget.dataset.index
    this.setData({ [`cropList[${index}].myArea`]: e.detail.value })
  },

  clearAll() {
    const cropList = this.data.cropList.map(item => ({ ...item, selected: false }))
    this.setData({ cropList, selectedCount: 0 })
  },

  async saveCrops() {
    const selectedCrops = this.data.cropList.filter(c => c.selected).map(c => c.cropName)

    // 检查登录状态
    const token = wx.getStorageSync('token')
    if (!token) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }

    this.setData({ saving: true })
    try {
      // 1. 保存作物选择
      const res = await request({
        url: '/api/wechat/my-crops',
        method: 'PUT',
        data: { crops: selectedCrops }
      })

      if (!res.success) {
        throw new Error(res.message || '保存失败')
      }

      // 2. 保存每个选中作物的播种日期 + 个人种植面积
      for (const item of this.data.cropList) {
        if (!item.selected) continue

        // 校验面积：留空表示不设置；填写则必须是 0~1000000 的数字
        const raw = String(item.myArea == null ? '' : item.myArea).trim()
        let areaMu = null
        if (raw !== '') {
          const n = Number(raw)
          if (!isFinite(n) || n <= 0 || n > 1000000) {
            throw new Error(`「${item.cropName}」的种植面积需为 0~1000000 之间的数字`)
          }
          areaMu = n
        }

        try {
          await request({
            url: '/api/wechat/crop-planting-date',
            method: 'PUT',
            data: {
              cropName: item.cropName,
              plantingDate: item.plantingDate || ''
            }
          })
          await request({
            url: '/api/wechat/crop-area',
            method: 'PUT',
            data: { cropName: item.cropName, areaMu }
          })
        } catch (e) {
          console.warn('保存播种日期/面积失败:', item.cropName, e.message)
        }
      }

      // 3. 同步到本地 userInfo
      const userInfo = wx.getStorageSync('userInfo') || {}
      userInfo.myCrops = selectedCrops
      wx.setStorageSync('userInfo', userInfo)
      app.globalData.userInfo = userInfo

      wx.showToast({ title: '保存成功', icon: 'success' })
      setTimeout(() => {
        wx.navigateBack()
      }, 1000)
    } catch (e) {
      console.error('保存失败:', e)
      wx.showToast({ title: e.message || '保存失败，请重试', icon: 'none' })
    } finally {
      this.setData({ saving: false })
    }
  }
})

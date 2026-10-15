const { request, BASE_URL } = require('../../utils/request')
const app = getApp()

Page({
  data: {
    currentWeather: null,
    forecast: [],
    allForecast: [],
    displayForecast: [],
    showAllForecast: false,
    warnings: [],
    warningCardClass: 'warning-card-default',
    farmIndex: [],
    villageCode: 'baini',
    villageName: '白坭镇',
    villageIndex: 0,
    villageNames: [],
    todayAdvice: '',
    loading: true,
    // 加载失败标记：真机上接口不通时页面不能只是"空白+一堆--"，
    // 要给出可操作的重试入口（常见于后端没启动 / 电脑 IP 变了）
    loadError: false,
    loadErrorMsg: '',
    weatherBgClass: 'bg-sunny'
  },

  onLoad() {
    this.initVillagesAndLoad()
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 0 })
    }
  },

  onPullDownRefresh() {
    this.loadData().then(() => wx.stopPullDownRefresh())
  },

  initVillagesAndLoad() {
    const villages = app.globalData.villages
    if (villages && villages.length > 0) {
      this.setData({
        villageNames: villages.map(v => v.name),
        villageIndex: villages.findIndex(v => v.code === this.data.villageCode) || 0
      })
      this.loadData()
    } else {
      const timer = setInterval(() => {
        const v = app.globalData.villages
        if (v && v.length > 0) {
          clearInterval(timer)
          this.setData({
            villageNames: v.map(item => item.name),
            villageIndex: v.findIndex(item => item.code === this.data.villageCode) || 0
          })
          this.loadData()
        }
      }, 200)
      setTimeout(() => {
        clearInterval(timer)
        if (!this.data.villageNames.length) {
          this.loadData()
        }
      }, 5000)
    }
  },

  // 点击错误提示上的「重试」按钮
  onRetryLoad() {
    this.loadData()
  },

  loadData() {
    this.setData({ loading: true, loadError: false, loadErrorMsg: '' })
    const code = this.data.villageCode
    return Promise.all([
      request({ url: '/api/weather/current', data: { villageCode: code } }),
      request({ url: '/api/weather/forecast', data: { villageCode: code } }),
      request({ url: '/api/warnings/active', data: { villageCode: code } }),
      request({ url: '/api/farm/report', data: { villageCode: code } })
    ]).then(([weatherRes, forecastRes, warningRes, farmIndexRes]) => {
      const forecast = forecastRes.success ? forecastRes.data : []
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const processedForecast = forecast.filter(item => {
        const targetDate = new Date(item.forecastDate)
        targetDate.setHours(0, 0, 0, 0)
        const diffDays = Math.floor((targetDate.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
        return diffDays >= -1
      }).map(item => {
        const targetDate = new Date(item.forecastDate)
        targetDate.setHours(0, 0, 0, 0)
        const diffDays = Math.floor((targetDate.getTime() - today.getTime()) / (1000 * 60 * 60 * 24))
        return {
          ...item,
          highTemperature: Math.round(Number(item.highTemperature)),
          lowTemperature: Math.round(Number(item.lowTemperature)),
          forecastDate: this.formatDate(item.forecastDate),
          weekLabel: this.getDayLabel(item.forecastDate),
          isYesterday: diffDays === -1,
          icon: this.getWeatherIcon(item.weatherType),
          farmingAdvice: item.farmingAdvice || ''
        }
      })
      const displayForecast = processedForecast.slice(0, 3)
      const currentWeather = weatherRes.success ? weatherRes.data : null
      let todayAdvice = ''
      if (currentWeather) {
        currentWeather.temperature = Math.round(Number(currentWeather.temperature))
        currentWeather.apparentTemperature = Math.round(Number(currentWeather.apparentTemperature))
        const todayForecast = processedForecast.find(f => f.weekLabel === '今天')
        if (todayForecast) {
          currentWeather.highTemperature = todayForecast.highTemperature
          currentWeather.lowTemperature = todayForecast.lowTemperature
          // 首屏直接给出今日农事行动指引（农户最关心的信息前置）
          todayAdvice = todayForecast.farmingAdvice || ''
        }
        currentWeather.icon = this.getWeatherIcon(currentWeather.weatherType)
      }
      const processedWarnings = this.filterWarnings(warningRes.success ? warningRes.data : [])
      this.setData({
        currentWeather: currentWeather,
        todayAdvice: todayAdvice,
        allForecast: processedForecast,
        displayForecast: displayForecast,
        showAllForecast: false,
        warnings: processedWarnings,
        warningCardClass: this.getWarningCardClass(processedWarnings),
        farmIndex: farmIndexRes.success ? farmIndexRes.data : [],
        weatherBgClass: this.getWeatherBgClass(currentWeather ? currentWeather.weatherType : ''),
        loading: false
      })
    }).catch((err) => {
      // 真机排查用：把失败原因带出来（域名未配置 / 网络不通 / 后端未启动 都会走这里）
      console.error('[首页] 加载失败', '当前接口地址=' + BASE_URL, err)
      const msg = (err && err.errMsg) ? err.errMsg : '未知错误'
      this.setData({
        loading: false,
        loadError: true,
        loadErrorMsg: msg
      })
      wx.showToast({ title: '数据加载失败，请检查后端', icon: 'none' })
    })
  },

  onVillageChange(e) {
    const villages = app.globalData.villages
    const idx = e.detail.value
    if (villages && villages[idx]) {
      app.globalData.villageCode = villages[idx].code
      app.globalData.villageName = villages[idx].name
      this.setData({
        villageIndex: idx,
        villageCode: villages[idx].code,
        villageName: villages[idx].name
      })
      this.loadData()
    }
  },

  filterWarnings(warnings) {
    const villageName = this.data.villageName || ''
    const levelTextMap = { red: '红色', orange: '橙色', yellow: '黄色', blue: '蓝色' }
    const filtered = !villageName ? warnings : warnings
      .filter(w => w.impactArea && w.impactArea.includes(villageName))
    return filtered.map(w => ({
      ...w,
      level: w.severity || 'yellow',
      levelText: levelTextMap[w.severity] || '黄色',
      title: w.title.replace(/^[\u4e00-\u9fa5]+市/, '')
    }))
  },

  // 根据所有活跃预警中最高等级，确定卡片整体配色
  getWarningCardClass(warnings) {
    if (!warnings || warnings.length === 0) return 'warning-card-default'
    const priority = { red: 4, orange: 3, yellow: 2, blue: 1 }
    let maxLevel = 'blue'
    let maxPriority = 0
    warnings.forEach(w => {
      const lv = w.severity || w.level || 'yellow'
      if (priority[lv] && priority[lv] > maxPriority) {
        maxPriority = priority[lv]
        maxLevel = lv
      }
    })
    return 'warning-card-' + maxLevel
  },

  formatDate(dateStr) {
    if (!dateStr) return ''
    const date = new Date(dateStr)
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    return month + '/' + day
  },

  getDayLabel(dateStr) {
    if (!dateStr) return ''
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const targetDate = new Date(dateStr)
    targetDate.setHours(0, 0, 0, 0)

    const diffTime = targetDate.getTime() - today.getTime()
    const diffDays = Math.floor(diffTime / (1000 * 60 * 60 * 24))

    if (diffDays === -1) return '昨天'
    if (diffDays === 0) return '今天'
    if (diffDays === 1) return '明天'

    const weekDays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    return weekDays[targetDate.getDay()]
  },

  getWeatherIcon(weatherType) {
    if (!weatherType) return '☀️'
    const iconMap = {
      '晴': '☀️',
      '多云': '⛅',
      '阴': '☁️',
      '小雨': '🌧️',
      '中雨': '🌧️',
      '大雨': '⛈️',
      '雷阵雨': '⛈️',
      '阵雨': '🌦️',
      '毛毛雨': '🌧️',
      '冻雨': '🌨️',
      '小雪': '❄️',
      '中雪': '❄️',
      '大雪': '❄️',
      '雾': '🌫️',
      '雾凇': '🌫️'
    }
    for (const key in iconMap) {
      if (weatherType.includes(key)) {
        return iconMap[key]
      }
    }
    return '☀️'
  },

  getWeatherBgClass(weatherType) {
    if (!weatherType) return 'bg-sunny'
    if (weatherType.includes('晴')) return 'bg-sunny'
    if (weatherType.includes('多云')) return 'bg-cloudy'
    if (weatherType.includes('阴')) return 'bg-overcast'
    if (weatherType.includes('雨') || weatherType.includes('雷')) return 'bg-rainy'
    if (weatherType.includes('雪')) return 'bg-snowy'
    if (weatherType.includes('雾') || weatherType.includes('霾')) return 'bg-foggy'
    return 'bg-sunny'
  },

  goToWeatherDetail() {
    if (this.data.showAllForecast) {
      this.setData({
        displayForecast: this.data.allForecast.slice(0, 3),
        showAllForecast: false
      })
    } else {
      this.setData({
        displayForecast: this.data.allForecast,
        showAllForecast: true
      })
    }
  },

  goToFarming() {
    wx.switchTab({ url: '/pages/farming/farming' })
  },

  goToAI() {
    wx.switchTab({ url: '/pages/ai/ai' })
  },

  goToReportDisaster() {
    wx.switchTab({ url: '/pages/community/community' })
  }
})

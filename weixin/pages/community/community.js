const { request, BASE_URL } = require('../../utils/request')
const { getAvatarByNickname, LOCAL_DEFAULT_AVATAR, DEFAULT_MALE_AVATAR, DEFAULT_FEMALE_AVATAR } = require('../../utils/avatar')
const app = getApp()

Page({
  data: {
    reports: [],
    loading: false,
    // 加载失败态：真机上接口不通时不能只弹一个 toast 就留一片空白，
    // 统一交给 <load-error> 组件给出「重新加载」入口
    loadError: false,
    loadErrorMsg: '',
    currentType: '',
    defaultMaleAvatar: DEFAULT_MALE_AVATAR,
    defaultFemaleAvatar: DEFAULT_FEMALE_AVATAR,
    typeLabels: {
      'pest': '病虫害',
      'disaster': '灾损',
      'growth': '长势',
      'help': '求助',
      'other': '其他'
    },
    statusLabels: {
      'pending': '待处理',
      'processing': '处理中',
      'resolved': '已解决',
      'closed': '已关闭'
    }
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 3 })
    }
    var newCode = app.globalData.villageCode || 'baini'
    if (this.data.villageCode !== newCode) {
      this.setData({ villageCode: newCode })
    }
    this.loadReports()
  },
  onLoad() {
    this.setData({ villageCode: app.globalData.villageCode || 'baini' })
  },

  onPullDownRefresh() {
    this.loadReports()
  },

  // 列表头像加载失败（真机上网络头像常因域名校验失败）→ 回退本地默认头像
  onAvatarError(e) {
    var idx = e.currentTarget.dataset.index
    var item = this.data.reports[idx]
    if (item && item.displayAvatar !== LOCAL_DEFAULT_AVATAR) {
      this.setData({ ['reports[' + idx + '].displayAvatar']: LOCAL_DEFAULT_AVATAR })
    }
  },

  // 点错误条上的「重新加载」
  onRetryLoad() {
    this.loadReports()
  },

  loadReports() {
    this.setData({ loading: true, loadError: false, loadErrorMsg: '' })
    var villageCode = this.data.villageCode || 'baini'
    var data = { villageCode: villageCode }
    if (this.data.currentType) {
      data.reportType = this.data.currentType
    }
    request({
      url: '/api/farm-reports',
      method: 'GET',
      data: data
    }).then(res => {
      if (res.success && res.data) {
        var list = res.data.map(item => {
          if (item.imageUrls) {
            item.parsedImages = item.imageUrls.split(',').filter(u => u).map(u => {
              if (u.startsWith('http://') || u.startsWith('https://')) return u
              return BASE_URL + u
            })
          }
          if (item.avatarUrl && !item.avatarUrl.startsWith('http')) {
            item.avatarUrl = BASE_URL + item.avatarUrl
          }
          item.displayAvatar = getAvatarByNickname(item.nickname, item.avatarUrl)
          // ISO 时间戳（如 2026-09-10T11:50:03.623275）→ 友好显示
          item.timeText = this.formatTime(item.createdAt)
          return item
        })
        this.setData({ reports: list })
      }
    }).catch((e) => {
      // 以前这里只弹一个 toast，一闪而过，页面还是空白 —— 现在落到页面级错误条上
      this.setData({
        loadError: true,
        loadErrorMsg: (e && e.errMsg) || (e && e.data && e.data.message) || (e && e.message) || ''
      })
    }).finally(() => {
      this.setData({ loading: false })
      wx.stopPullDownRefresh()
    })
  },

  // 今天 14:32 / 昨天 14:32 / 9月10日 / 2025年9月10日
  formatTime(iso) {
    if (!iso) return ''
    var d = new Date(String(iso).replace(' ', 'T'))
    if (isNaN(d.getTime())) return String(iso).slice(0, 10)
    var now = new Date()
    var pad = function (n) { return String(n).padStart(2, '0') }
    var sameDay = function (a, b) {
      return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
    }
    var yesterday = new Date(now)
    yesterday.setDate(now.getDate() - 1)
    var hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
    if (sameDay(d, now)) return '今天 ' + hm
    if (sameDay(d, yesterday)) return '昨天 ' + hm
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm
    return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日'
  },

  filterByType(e) {
    var type = e.currentTarget.dataset.type
    this.setData({ currentType: type })
    this.loadReports()
  },

  goDetail(e) {
    var id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/community/detail/detail?id=' + id })
  },

  goCreate() {
    var openid = wx.getStorageSync('openid')
    if (!openid) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/community/create/create' })
  },

  previewImage(e) {
    var url = e.currentTarget.dataset.url
    var item = this.data.reports.find(function(r) {
      return r.parsedImages && r.parsedImages.indexOf(url) !== -1
    })
    wx.previewImage({
      current: url,
      urls: item ? item.parsedImages : [url]
    })
  }
})

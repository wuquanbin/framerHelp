App({
  globalData: {
    userInfo: null,
    villageCode: 'baini',
    villageName: '白坭镇',
    villages: [],
    unreadCount: 0 // 管理员回复的未读数（驱动"我的"tabBar 角标和"我的回复"入口角标）
  },
  onLaunch() {
    this.loadVillages()
    // 启动时拉一次未读数，之后每 60 秒轮询；未登录时方法内部会直接跳过
    this.refreshUnreadCount()
    this.unreadTimer = setInterval(() => this.refreshUnreadCount(), 60000)
  },
  loadVillages() {
    const { request } = require('./utils/request')
    request({ url: '/api/meta/villages' }).then(res => {
      if (res.success) {
        this.globalData.villages = res.data
      }
    }).catch(() => {})
  },
  // 拉取未读回复数，同步全局状态与 tabBar 角标，返回 Promise 供页面更新自身入口角标
  refreshUnreadCount() {
    if (!wx.getStorageSync('token')) {
      this.setUnreadCount(0)
      return Promise.resolve()
    }
    const { request } = require('./utils/request')
    return request({ url: '/api/wechat/unread-responses-count' }).then(res => {
      if (res.success) this.setUnreadCount(res.data.unread || 0)
    }).catch(() => {})
  },
  // 更新未读数并同步到当前页面挂载的自定义 tabBar
  setUnreadCount(n) {
    this.globalData.unreadCount = n
    this.syncTabBarBadge()
  },
  syncTabBarBadge() {
    try {
      const pages = getCurrentPages()
      const cur = pages[pages.length - 1]
      if (cur && typeof cur.getTabBar === 'function') {
        const tabBar = cur.getTabBar()
        if (tabBar) tabBar.setData({ unreadCount: this.globalData.unreadCount })
      }
    } catch (e) { /* 页面未挂载 tabBar 时忽略 */ }
  },
  // 进入"我的回复"页时调用：前端立即清零角标，同时通知后端记录本次已读时间
  markResponsesRead() {
    this.setUnreadCount(0)
    if (!wx.getStorageSync('token')) return Promise.resolve()
    const { post } = require('./utils/request')
    return post('/api/wechat/read-responses', {}).catch(() => {})
  }
})

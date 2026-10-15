Component({
  data: {
    selected: 0,
    unreadCount: 0, // "我的回复"未读数，由 app.syncTabBarBadge 同步
    list: [
      { pagePath: '/pages/index/index', text: '首页' },
      { pagePath: '/pages/farming/farming', text: '农事' },
      { pagePath: '/pages/ai/ai', text: 'AI', isAi: true },
      { pagePath: '/pages/community/community', text: '农情' },
      { pagePath: '/pages/profile/profile', text: '我的' }
    ]
  },

  // 每次所在 tab 页显示时，从全局数据同步未读角标（保证跨页面轮询结果及时反映）
  pageLifetimes: {
    show() {
      try {
        const app = getApp()
        if (app && app.globalData) {
          this.setData({ unreadCount: app.globalData.unreadCount || 0 })
        }
      } catch (e) {}
    }
  },

  methods: {
    switchTab(e) {
      const data = e.currentTarget.dataset
      const url = data.path

      wx.switchTab({ url: url })
    }
  }
})

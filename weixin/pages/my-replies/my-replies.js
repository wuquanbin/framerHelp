const { request } = require('../../utils/request')
const app = getApp()

// 意见反馈类型枚举 → 中文（后端 feedbackType 默认 suggestion）
const FEEDBACK_TYPE_TEXT = {
  suggestion: '功能建议',
  bug: '问题报错',
  complaint: '投诉建议',
  praise: '表扬',
  other: '其他'
}

Page({
  data: {
    replies: [],
    loading: false,
    isLoggedIn: false,
    // 加载失败态：拉不到回复列表时页面只剩一句「暂无回复」会误导用户，
    // 交给 <load-error> 组件明确区分「真的没有」和「没加载出来」
    loadError: false,
    loadErrorMsg: ''
  },

  onLoad() {
    const token = wx.getStorageSync('token')
    if (!token) {
      this.setData({ isLoggedIn: false })
      return
    }
    this.setData({ isLoggedIn: true })
    this.loadReplies()
  },

  onPullDownRefresh() {
    this.loadReplies()
  },

  // iOS 风格时间：今天 14:32 / 昨天 / 9月3日
  formatTime(iso) {
    if (!iso) return ''
    const d = new Date(String(iso).replace(' ', 'T'))
    if (isNaN(d.getTime())) return String(iso).slice(0, 10)
    const now = new Date()
    const pad = n => String(n).padStart(2, '0')
    const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
    const yesterday = new Date(now)
    yesterday.setDate(now.getDate() - 1)
    const hm = pad(d.getHours()) + ':' + pad(d.getMinutes())
    if (sameDay(d, now)) return '今天 ' + hm
    if (sameDay(d, yesterday)) return '昨天 ' + hm
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日'
    return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日'
  },

  // 点错误条上的「重新加载」
  onRetryLoad() {
    this.loadReplies()
  },

  loadReplies() {
    if (!this.data.isLoggedIn) {
      wx.stopPullDownRefresh()
      return
    }
    this.setData({ loading: true, loadError: false, loadErrorMsg: '' })
    request({
      url: '/api/wechat/my-responses',
      method: 'GET'
    }).then(res => {
      if (res.success && res.data) {
        const list = res.data.map(it => ({
          ...it,
          timeText: this.formatTime(it.createdAt),
          // 意见反馈的 title 是类型枚举，转中文；农情上报标题保持原样
          titleText: it.source === 'feedback'
            ? (FEEDBACK_TYPE_TEXT[it.title] || it.title || '意见反馈')
            : it.title
        }))
        this.setData({ replies: list })
        // 用户进入本页即视为已看：通知后端记录已读时间，并立即清除各处未读角标
        if (app && app.markResponsesRead) app.markResponsesRead()
      }
    }).catch((e) => {
      this.setData({
        loadError: true,
        loadErrorMsg: (e && e.errMsg) || (e && e.data && e.data.message) || (e && e.message) || ''
      })
    }).finally(() => {
      this.setData({ loading: false })
      wx.stopPullDownRefresh()
    })
  },

  goReportDetail(e) {
    const id = e.currentTarget.dataset.reportid
    if (id) {
      wx.navigateTo({ url: '/pages/community/detail/detail?id=' + id })
    }
  },

  onWechatLogin() {
    wx.navigateTo({ url: '/pages/profile/profile' })
  }
})

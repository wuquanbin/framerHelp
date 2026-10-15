const { request, BASE_URL } = require('../../../utils/request')
const { getAvatarByNickname, LOCAL_DEFAULT_AVATAR, DEFAULT_MALE_AVATAR, DEFAULT_FEMALE_AVATAR } = require('../../../utils/avatar')
const app = getApp()

Page({
  data: {
    report: null,
    responses: [],
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
    },
    responseTypeLabels: {
      'reply': '回复',
      'guidance': '技术指导',
      'dispatch': '派人处理',
      'close': '关闭'
    }
  },

  onLoad(options) {
    if (options.id) {
      this.loadDetail(options.id)
    }
  },

  // 头像加载失败（真机上网络头像常因域名校验失败）→ 回退本地默认头像。
  // data-scope 区分主楼作者与回复列表
  onAvatarError(e) {
    var scope = e.currentTarget.dataset.scope
    var index = e.currentTarget.dataset.index
    if (scope === 'report') {
      if (this.data.report && this.data.report.displayAvatar !== LOCAL_DEFAULT_AVATAR) {
        this.setData({ 'report.displayAvatar': LOCAL_DEFAULT_AVATAR })
      }
    } else if (scope === 'response' && index !== undefined) {
      var item = this.data.responses[index]
      if (item && item.displayAvatar !== LOCAL_DEFAULT_AVATAR) {
        this.setData({ ['responses[' + index + '].displayAvatar']: LOCAL_DEFAULT_AVATAR })
      }
    }
  },

  loadDetail(id) {
    request({
      url: '/api/farm-reports/' + id,
      method: 'GET'
    }).then(res => {
      if (res.success && res.data) {
        var report = res.data
        if (report.imageUrls) {
          report.parsedImages = report.imageUrls.split(',').filter(u => u).map(u => {
            if (u.startsWith('http://') || u.startsWith('https://')) return u
            return BASE_URL + u
          })
        }
        if (report.avatarUrl && !report.avatarUrl.startsWith('http')) {
          report.avatarUrl = BASE_URL + report.avatarUrl
        }
        report.displayAvatar = getAvatarByNickname(report.nickname, report.avatarUrl)
        this.setData({ report: report })
      }
    }).catch(() => {
      wx.showToast({ title: '加载失败', icon: 'none' })
    })

    request({
      url: '/api/farm-reports/' + id + '/responses',
      method: 'GET'
    }).then(res => {
      if (res.success && res.data) {
        var responses = res.data.map(item => ({
          ...item,
          displayAvatar: getAvatarByNickname(item.responderName, item.responderAvatar)
        }))
        this.setData({ responses: responses })
      }
    }).catch(() => {})
  },

  previewImage(e) {
    var url = e.currentTarget.dataset.url
    this.setData({
      currentImage: url
    })
    wx.previewImage({
      current: url,
      urls: this.data.report.parsedImages || [url]
    })
  }
})

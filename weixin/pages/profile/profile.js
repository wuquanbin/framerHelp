const { get, post, put, relogin, BASE_URL } = require('../../utils/request')
const { LOCAL_DEFAULT_AVATAR } = require('../../utils/avatar')
const app = getApp()

Page({
  data: {
    userInfo: null,
    isLoggedIn: false,
    assistanceRecords: [],
    feedbacks: [],
    showAssistancePanel: false,
    showFeedbackPanel: false,
    showFeedbackForm: false,
    feedbackContent: '',
    feedbackSubmitting: false,
    notifyWarning: true,
    notifyFarming: false,
    unreadCount: 0,
    // "已选作物"入口文案
    myCropsText: '点击选择我的作物',
    // 弹窗相关
    showPhoneModal: false,
    showVillageModal: false,
    phoneInput: '',
    villageList: [],
  },

  // 将相对路径的 avatarUrl 转为完整 URL
  resolveAvatarUrl(url) {
    if (!url) return ''
    if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
      return url
    }
    return BASE_URL + url
  },

  // 将在用的网络头像预先下载为本地临时文件。
  // 背景：部分安卓真机上小程序**渲染层**直连 http 局域网地址会被拦（手机浏览器打开同一个地址却正常），
  // 而 wx.downloadFile 走**逻辑层**，与 wx.request 同网络环境，能正常下载。这里提前转成本地文件，
  // 让 <image> 读本地不再依赖渲染层联网。
  prefetchAvatar(url) {
    if (!url || !/^https?:\/\//.test(url)) return
    wx.downloadFile({
      url,
      success: (res) => {
        if (res.statusCode === 200 && res.tempFilePath) {
          console.log('[头像] downloadFile 成功，改用临时文件展示')
          this.setData({ 'userInfo.avatarUrl': res.tempFilePath })
        }
      },
      fail: (err) => {
        console.warn('[头像] downloadFile 失败，仍交给 <image> 自行尝试:', err.errMsg)
      }
    })
  },

  // 头像加载失败（真机上常见：防火墙拦入站 / 手机不在同一网段 / 渲染层直连 http 局域网失败）。
  // 处理顺序：先走 wx.downloadFile 逻辑层中转（与 wx.request 同一套网络环境，API 能通它就能通），
  // 再失败才回退本地默认头像。只改展示层，不动 storage 里原始的 avatarUrl。
  onAvatarError(e) {
    const detail = e && e.detail ? e.detail.errMsg : ''
    const url = this.data.userInfo ? this.data.userInfo.avatarUrl : ''
    console.error('[头像] <image> 直连失败:', detail, '| url =', url)

    if (!url || !/^https?:\/\//.test(url)) {
      this.setData({ 'userInfo.avatarUrl': LOCAL_DEFAULT_AVATAR })
      return
    }
    wx.downloadFile({
      url,
      success: (res) => {
        if (res.statusCode === 200 && res.tempFilePath) {
          console.log('[头像] downloadFile 中转成功，改用临时文件展示')
          this.setData({ 'userInfo.avatarUrl': res.tempFilePath })
        } else {
          console.error('[头像] downloadFile 状态码异常:', res.statusCode)
          this.setData({ 'userInfo.avatarUrl': LOCAL_DEFAULT_AVATAR })
        }
      },
      fail: (err) => {
        console.error('[头像] downloadFile 也失败:', err.errMsg,
          '→ 手机访问不到后端，请检查：手机与电脑是否同一 WiFi / 电脑 IP 是否仍为 192.168.0.102 / 防火墙是否放行 8082 入站')
        this.setData({ 'userInfo.avatarUrl': LOCAL_DEFAULT_AVATAR })
      }
    })
  },

  onLoad() {
    this.restoreSession()
    this.restoreSettings()
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().setData({ selected: 4 })
    }
    if (this.data.isLoggedIn) {
      this.ensureUserInfoComplete().then(() => this.updateMyCropsText())
      this.updateMyCropsText()
      this.loadAssistanceRecords()
      this.loadMyFeedbacks()
      this.refreshUnread()
    }
  },

  // ==================== 已选作物入口 ====================
  // 更新"已选 N 种作物"文案（从作物选择页返回时 onShow 会重新触发。
  // my-crops 保存后只写 storage/globalData，不通知本页 data，
  // 因此这里优先读 globalData，避免显示旧的选中数量）
  updateMyCropsText() {
    const latest = app.globalData.userInfo || this.data.userInfo || {}
    const crops = latest.myCrops || []
    if (crops.length > 0) {
      this.setData({ myCropsText: '已选 ' + crops.length + ' 种作物' })
    } else {
      this.setData({ myCropsText: '点击选择我的作物' })
    }
  },

  // 跳转到我的作物选择页
  goToMyCrops() {
    if (!this.data.isLoggedIn) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    wx.navigateTo({ url: '/pages/my-crops/my-crops' })
  },

  // 刷新"我的回复"入口未读角标
  refreshUnread() {
    if (!app.refreshUnreadCount) return
    app.refreshUnreadCount().then(() => {
      this.setData({ unreadCount: app.globalData.unreadCount || 0 })
    }).catch((e) => {
      // 角标刷不出来不该影响整页，但也不能完全静默 —— 真机上「红点一直不出现」
      // 排查时全靠这一行日志
      console.warn('[profile] 未读回复数刷新失败', (e && e.errMsg) || (e && e.data && e.data.message) || e)
    })
  },

  // 阻止事件冒泡（弹窗内容区域点击不关闭弹窗）
  stopPropagation() {
    // 空方法，仅用于 catchtap 阻止冒泡
  },

  // ==================== 绑定手机号弹窗 ====================
  openPhoneModal() {
    if (!this.data.isLoggedIn) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    this.setData({
      showPhoneModal: true,
      phoneInput: this.data.userInfo.phone || ''
    })
  },

  closePhoneModal() {
    this.setData({ showPhoneModal: false })
  },

  onPhoneInput(e) {
    this.setData({ phoneInput: e.detail.value })
  },

  async confirmPhone() {
    const phone = this.data.phoneInput.trim()
    if (!phone) {
      wx.showToast({ title: '请输入手机号', icon: 'none' })
      return
    }
    if (!/^1[3-9]\d{9}$/.test(phone)) {
      wx.showToast({ title: '手机号格式不正确', icon: 'none' })
      return
    }
    try {
      const res = await put('/api/wechat/profile', { phone })
      if (res.success) {
        const userInfo = { ...this.data.userInfo, phone: res.data.phone }
        app.globalData.userInfo = userInfo
        wx.setStorageSync('userInfo', userInfo)
        this.setData({ userInfo, showPhoneModal: false })
        wx.showToast({ title: '绑定成功', icon: 'success' })
      } else {
        wx.showToast({ title: res.message || '绑定失败', icon: 'none' })
      }
    } catch (e) {
      console.error('绑定手机号失败:', e)
      wx.showToast({ title: '绑定失败，请重试', icon: 'none' })
    }
  },

  // ==================== 选择村庄弹窗 ====================
  async openVillageModal() {
    if (!this.data.isLoggedIn) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    // 加载村庄列表
    if (this.data.villageList.length === 0) {
      try {
        const res = await get('/api/meta/villages')
        if (res.success && res.data) {
          this.setData({ villageList: res.data })
        }
      } catch (e) {
        console.error('加载村庄列表失败:', e)
      }
    }
    this.setData({ showVillageModal: true })
  },

  closeVillageModal() {
    this.setData({ showVillageModal: false })
  },

  async selectVillage(e) {
    const code = e.currentTarget.dataset.code
    const name = e.currentTarget.dataset.name
    try {
      const res = await put('/api/wechat/profile', { villageCode: code })
      if (res.success) {
        const userInfo = {
          ...this.data.userInfo,
          villageCode: res.data.villageCode,
          villageName: res.data.villageName
        }
        app.globalData.userInfo = userInfo
        app.globalData.villageCode = res.data.villageCode
        app.globalData.villageName = res.data.villageName
        wx.setStorageSync('userInfo', userInfo)
        wx.setStorageSync('villageCode', res.data.villageCode)
        wx.setStorageSync('villageName', res.data.villageName)
        this.setData({ userInfo, showVillageModal: false })
        wx.showToast({ title: '已切换到' + name, icon: 'success' })
      } else {
        wx.showToast({ title: res.message || '切换失败', icon: 'none' })
      }
    } catch (e) {
      console.error('切换村庄失败:', e)
      wx.showToast({ title: '切换失败，请重试', icon: 'none' })
    }
  },

  // 兼容旧用户：本地 userInfo 缺少 phone/villageName 时，静默重新登录补全
  async ensureUserInfoComplete() {
    const userInfo = this.data.userInfo
    if (userInfo && userInfo.phone !== undefined && userInfo.villageName !== undefined) {
      return
    }
    try {
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })
      const res = await post('/api/wechat/login', { code: loginRes.code })
      if (!res.success) return
      const data = res.data
      const updatedInfo = {
        ...userInfo,
        phone: data.phone || '',
        villageCode: data.villageCode || '',
        villageName: data.villageName || '',
        myCrops: data.myCrops || [],
      }
      app.globalData.userInfo = updatedInfo
      wx.setStorageSync('userInfo', updatedInfo)
      this.setData({ userInfo: updatedInfo })
    } catch (e) {
      // 静默失败，不影响用户体验
      console.warn('刷新用户信息失败:', e)
    }
  },

  restoreSession() {
    const token = wx.getStorageSync('token')
    const userInfo = wx.getStorageSync('userInfo')
    if (token && userInfo) {
      app.globalData.token = token
      // 兼容旧数据：如果 avatarUrl 是相对路径，补全为完整 URL
      if (userInfo.avatarUrl) {
        userInfo.avatarUrl = this.resolveAvatarUrl(userInfo.avatarUrl)
      }
      app.globalData.userInfo = userInfo
      this.setData({ isLoggedIn: true, userInfo })
      this.prefetchAvatar(userInfo.avatarUrl)
      this.loadAssistanceRecords()
    }
  },

  restoreSettings() {
    const notifyWarning = wx.getStorageSync('notifyWarning')
    const notifyFarming = wx.getStorageSync('notifyFarming')
    const data = {}
    if (notifyWarning !== '' && notifyWarning !== undefined) {
      data.notifyWarning = notifyWarning
    }
    if (notifyFarming !== '' && notifyFarming !== undefined) {
      data.notifyFarming = notifyFarming
    }
    if (Object.keys(data).length > 0) {
      this.setData(data)
    }
  },

  onGridTap(e) {
    const type = e.currentTarget.dataset.type
    switch (type) {
      case 'assistance':
        this.setData({ showAssistancePanel: !this.data.showAssistancePanel, showFeedbackPanel: false })
        if (this.data.showAssistancePanel) {
          setTimeout(() => this.scrollToSection('section-assistance'), 100)
        }
        break
      case 'feedback':
        this.setData({ showFeedbackPanel: !this.data.showFeedbackPanel, showAssistancePanel: false })
        if (this.data.showFeedbackPanel) {
          setTimeout(() => this.scrollToSection('section-feedback'), 100)
        }
        break
      case 'setting':
        this.scrollToSection('section-setting')
        break
      case 'my-replies':
        if (!this.data.isLoggedIn) {
          wx.showToast({ title: '请先登录', icon: 'none' })
          return
        }
        wx.navigateTo({ url: '/pages/my-replies/my-replies' })
        break
      case 'about':
        this.scrollToSection('section-about')
        break
    }
  },

  scrollToSection(id) {
    wx.createSelectorQuery()
      .select('#' + id)
      .boundingClientRect(function (rect) {
        if (rect) {
          wx.pageScrollTo({
            scrollTop: rect.top + wx.getSystemInfoSync().scrollTop - 20,
            duration: 300,
          })
        }
      })
      .exec()
  },

  toggleAssistance() {
    this.setData({ showAssistancePanel: !this.data.showAssistancePanel })
  },

  async onWechatLogin() {
    try {
      const loginRes = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })
      const res = await post('/api/wechat/login', { code: loginRes.code })
      if (!res.success) throw new Error(res.message)
      const data = res.data
      const userInfo = {
        id: data.userId,
        nickname: data.nickname,
        avatarUrl: this.resolveAvatarUrl(data.avatarUrl),
        phone: data.phone || '',
        villageCode: data.villageCode || '',
        villageName: data.villageName || '',
        myCrops: data.myCrops || [],
      }
      app.globalData.token = data.token
      app.globalData.userInfo = userInfo
      wx.setStorageSync('token', data.token)
      wx.setStorageSync('userInfo', userInfo)
      wx.setStorageSync('openid', data.openid)
      
      // 保存通知设置到本地
      const notifyWarning = data.notifyWarning !== undefined ? data.notifyWarning : true
      const notifyFarming = data.notifyFarming !== undefined ? data.notifyFarming : true
      wx.setStorageSync('notifyWarning', notifyWarning)
      wx.setStorageSync('notifyFarming', notifyFarming)
      
      this.setData({ isLoggedIn: true, userInfo, notifyWarning, notifyFarming })
      this.prefetchAvatar(userInfo.avatarUrl)
      this.updateMyCropsText()
      this.loadAssistanceRecords()
      this.refreshUnread()
      wx.showToast({ title: '登录成功', icon: 'success' })
      // 注意：订阅消息授权必须由用户点击手势触发，不能在登录后自动弹出
      // 用户开启通知开关时会自动弹授权框
    } catch (e) {
      wx.showToast({ title: '登录失败', icon: 'none' })
      console.error('登录失败:', e)
    }
  },

  // 请求订阅消息授权（必须由用户点击手势触发）
  // onComplete 回调参数：{ warningAccept, farmingAccept }
  requestSubscribePermission(onComplete) {
    const TEMPLATE_IDS = {
      warning: 'dD3MqmuRlcuV0_9388jNH6IH3k1ymxPUmWv39mFrn2c',
      farming: 'V7qRUogUXi3Dzvq-M7JrDfuCb6_4WUP539oky2h_-TM'
    }
    const tmplIds = []
    if (TEMPLATE_IDS.warning) tmplIds.push(TEMPLATE_IDS.warning)
    if (TEMPLATE_IDS.farming) tmplIds.push(TEMPLATE_IDS.farming)

    if (tmplIds.length === 0) {
      console.log('订阅消息模板ID未配置，跳过订阅请求')
      if (onComplete) onComplete({ warningAccept: true, farmingAccept: true })
      return
    }

    if (!wx.requestSubscribeMessage) {
      console.log('当前微信版本不支持订阅消息')
      if (onComplete) onComplete({ warningAccept: true, farmingAccept: true })
      return
    }

    wx.requestSubscribeMessage({
      tmplIds: tmplIds,
      success: (res) => {
        console.log('订阅消息授权结果:', res)
        let warningAccept = false
        let farmingAccept = false
        if (TEMPLATE_IDS.warning && res[TEMPLATE_IDS.warning] === 'accept') {
          warningAccept = true
        }
        if (TEMPLATE_IDS.farming && res[TEMPLATE_IDS.farming] === 'accept') {
          farmingAccept = true
        }
        if (onComplete) onComplete({ warningAccept, farmingAccept })
      },
      fail: (err) => {
        console.log('订阅消息授权失败:', err)
        // 用户拒绝或取消，不强制，回调中按未授权处理
        if (onComplete) onComplete({ warningAccept: false, farmingAccept: false })
      }
    })
  },

  // 同步通知设置到后端
  async syncNotifySettings(notifyWarning, notifyFarming) {
    try {
      const token = wx.getStorageSync('token')
      if (!token) return
      
      this.setData({ notifyWarning, notifyFarming })
      wx.setStorageSync('notifyWarning', notifyWarning)
      wx.setStorageSync('notifyFarming', notifyFarming)
      
      await put('/api/wechat/notify-settings', { notifyWarning, notifyFarming })
    } catch (e) {
      console.error('同步通知设置失败:', e)
    }
  },

  onChooseAvatar() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const tempFilePath = res.tempFilePaths[0]
        this.compressAndUpload(tempFilePath)
      },
      fail: (err) => {
        // 用户主动取消选择，不提示错误
        if (err.errMsg && err.errMsg.indexOf('cancel') !== -1) {
          return
        }
        console.error('选择图片失败:', err)
        wx.showToast({ title: '选择图片失败，请检查相机/相册权限', icon: 'none' })
      }
    })
  },

  compressAndUpload(filePath) {
    wx.showLoading({ title: '上传中...' })
    // 检查 compressImage API 是否可用
    if (wx.canIUse('compressImage')) {
      wx.compressImage({
        src: filePath,
        quality: 60,
        compressWidth: 300,
        success: (compressRes) => {
          this.doUpload(compressRes.tempFilePath)
        },
        fail: (err) => {
          console.warn('图片压缩失败，使用原图上传:', err)
          this.doUpload(filePath)
        }
      })
    } else {
      // 旧版微信不支持 compressImage，直接用原图上传
      this.doUpload(filePath)
    }
  },

  doUpload(filePath, isRetry) {
    const token = wx.getStorageSync('token')
    wx.uploadFile({
      url: BASE_URL + '/api/wechat/avatar',
      filePath: filePath,
      name: 'file',
      header: {
        'Authorization': 'Bearer ' + token
      },
      success: (res) => {
        wx.hideLoading()
        if (res.statusCode === 200) {
          try {
            const result = JSON.parse(res.data)
            if (result.success) {
              const avatarUrl = this.resolveAvatarUrl(result.data.avatarUrl)
              const newUserInfo = { ...this.data.userInfo, avatarUrl }
              app.globalData.userInfo = newUserInfo
              wx.setStorageSync('userInfo', newUserInfo)
              this.setData({ userInfo: newUserInfo })
              this.prefetchAvatar(avatarUrl)
              wx.showToast({ title: '头像更新成功', icon: 'success' })
            } else {
              wx.showToast({ title: result.message || '上传失败', icon: 'none' })
            }
          } catch (e) {
            wx.showToast({ title: '解析响应失败', icon: 'none' })
          }
        } else if ((res.statusCode === 401 || res.statusCode === 403) && !isRetry) {
          // token 过期：静默重新登录后重传一次
          relogin()
            .then(() => {
              app.globalData.token = wx.getStorageSync('token')
              this.doUpload(filePath, true)
            })
            .catch(() => {
              wx.showToast({ title: '登录已过期，请重新登录', icon: 'none' })
            })
        } else {
          wx.showToast({ title: '上传失败(' + res.statusCode + ')', icon: 'none' })
        }
      },
      fail: (err) => {
        wx.hideLoading()
        console.error('头像上传失败:', err)
        wx.showToast({ title: '网络错误，请重试', icon: 'none' })
      }
    })
  },

  onEditNickname() {
    wx.showModal({
      title: '修改昵称',
      editable: true,
      placeholderText: '请输入昵称',
      content: this.data.userInfo?.nickname || '',
      success: async (res) => {
        if (res.confirm && res.content && res.content.trim()) {
          const newNickname = res.content.trim()
          try {
            const result = await put('/api/wechat/profile', { nickname: newNickname })
            if (result.success) {
              const newUserInfo = { ...this.data.userInfo, nickname: newNickname }
              app.globalData.userInfo = newUserInfo
              wx.setStorageSync('userInfo', newUserInfo)
              this.setData({ userInfo: newUserInfo })
              wx.showToast({ title: '昵称已更新', icon: 'success' })
            } else {
              wx.showToast({ title: result.message || '修改失败', icon: 'none' })
            }
          } catch (e) {
            wx.showToast({ title: '修改失败', icon: 'none' })
          }
        }
      }
    })
  },

  onLogout() {
    wx.showModal({
      title: '提示',
      content: '确定退出登录？退出后将无法接收灾害预警和农事提醒通知',
      success: (res) => {
        if (res.confirm) {
          app.globalData.token = ''
          app.globalData.userInfo = null
          wx.removeStorageSync('token')
          wx.removeStorageSync('userInfo')
          wx.removeStorageSync('openid')
          wx.removeStorageSync('notifyWarning')
          wx.removeStorageSync('notifyFarming')
          this.setData({
            isLoggedIn: false,
            userInfo: null,
            assistanceRecords: [],
            feedbacks: [],
            showAssistancePanel: false,
            showFeedbackPanel: false,
            showFeedbackForm: false,
            notifyWarning: false,
            notifyFarming: false,
            unreadCount: 0,
          })
          app.setUnreadCount(0)
          wx.showToast({ title: '已退出登录', icon: 'success' })
        }
      },
    })
  },

  // 灾害预警通知开关
  onNotifyWarningChange(e) {
    const checked = e.detail.value
    if (checked) {
      // 开启时先弹订阅消息授权框（必须由用户点击触发）
      this.requestSubscribePermission((result) => {
        // 授权后同步设置，灾害预警用用户当前选择，农事提醒保持原状态
        this.syncNotifySettings(true, this.data.notifyFarming)
      })
    } else {
      this.syncNotifySettings(false, this.data.notifyFarming)
    }
  },

  // 农事提醒通知开关
  onNotifyFarmingChange(e) {
    const checked = e.detail.value
    if (checked) {
      // 开启时先弹订阅消息授权框（必须由用户点击触发）
      this.requestSubscribePermission((result) => {
        this.syncNotifySettings(this.data.notifyWarning, true)
      })
    } else {
      this.syncNotifySettings(this.data.notifyWarning, false)
    }
  },

  async loadAssistanceRecords() {
    try {
      const res = await get('/api/wechat/assistance')
      const assistanceRecords = (res.success) ? res.data : []
      this.setData({ assistanceRecords })
    } catch (e) {
      console.error('加载帮扶记录失败:', e)
    }
  },

  // iOS 风格时间：今天 14:32 / 昨天 14:32 / 9月3日 / 2025年9月3日
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
    if (d.getFullYear() === now.getFullYear()) return (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + hm
    return d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日'
  },

  async loadMyFeedbacks() {
    try {
      const res = await get('/api/wechat/feedbacks')
      const list = (res.success) ? res.data : []
      // 后端返回的原始时间（如 2026-09-10T17:42:14.987398）格式化为友好文本
      const feedbacks = list.map(it => ({
        ...it,
        timeText: this.formatTime(it.createdAt)
      }))
      this.setData({ feedbacks })
    } catch (e) {
      console.error('加载反馈列表失败:', e)
    }
  },

  toggleFeedback() {
    this.setData({ 
      showFeedbackPanel: !this.data.showFeedbackPanel,
      showFeedbackForm: false 
    })
  },

  openFeedbackForm() {
    this.setData({ showFeedbackForm: true })
  },

  closeFeedbackForm() {
    this.setData({ showFeedbackForm: false, feedbackContent: '' })
  },

  onFeedbackInput(e) {
    this.setData({ feedbackContent: e.detail.value })
  },

  async submitFeedback() {
    if (!this.data.feedbackContent.trim()) {
      wx.showToast({ title: '请输入反馈内容', icon: 'none' })
      return
    }
    if (!this.data.isLoggedIn) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }
    this.setData({ feedbackSubmitting: true })
    try {
      await post('/api/wechat/feedback', {
        content: this.data.feedbackContent.trim(),
      })
      wx.showToast({ title: '提交成功', icon: 'success' })
      this.setData({ feedbackContent: '', showFeedbackForm: false })
      this.loadMyFeedbacks()
    } catch (e) {
      wx.showToast({ title: '提交失败', icon: 'none' })
    } finally {
      this.setData({ feedbackSubmitting: false })
    }
  },

  feedbackStatusLabel(s) {
    const map = { pending: '待处理', replied: '已回复', resolved: '已解决' }
    return map[s] || s
  },

  feedbackStatusClass(s) {
    if (s === 'pending') return 'status-pending'
    if (s === 'replied') return 'status-replied'
    return 'status-resolved'
  },

  clearCache() {
    const that = this
    wx.showModal({
      title: '提示',
      content: '确定清除本地缓存？',
      success(res) {
        if (res.confirm) {
          wx.clearStorageSync()
          that.setData({
            isLoggedIn: false,
            userInfo: null,
            assistanceRecords: [],
            feedbacks: [],
            showAssistancePanel: false,
            showFeedbackPanel: false,
            showFeedbackForm: false,
            unreadCount: 0,
          })
          app.setUnreadCount(0)
          wx.showToast({ title: '已清除', icon: 'success' })
        }
      },
    })
  },
})

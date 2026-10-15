const { request, post, relogin, BASE_URL } = require('../../../utils/request')
const app = getApp()

Page({
  data: {
    reportType: 'pest',
    title: '',
    description: '',
    imageList: [],
    cropName: '',
    growthStage: '播种期',
    growthStageIndex: 0,
    growthStageOptions: ['播种期', '出苗期', '幼苗期', '分蘖期', '拔节期', '抽穗期', '开花期', '灌浆期', '结果期', '膨大期', '成熟期', '采收期', '其他'],
    urgency: 'low',
    cropOptions: ['丝苗米', '黑皮冬瓜', '粉葛', '单丛茶', '荔枝', '百合花', '蝴蝶兰', '年桔', '兰花', '早稻', '莲藕', '桂花鱼', '生菜', '菜心', '晚稻'],
    submitting: false,
    // ===== AI 帮我整理 =====
    parsing: false,
    polishHint: '大概说说情况，点一下自动补全标题和分类',
    aiSuggestion: '',
    // 白名单：模型返回的取值不可信，越界的直接丢弃，不让脏值写进表单
    reportTypes: ['pest', 'disaster', 'growth', 'help'],
    urgencyOptions: ['low', 'medium', 'high']
  },

  selectType(e) {
    this.setData({ reportType: e.currentTarget.dataset.type })
  },

  onTitleInput(e) {
    this.setData({ title: e.detail.value })
  },

  onDescInput(e) {
    this.setData({ description: e.detail.value })
  },

  onCropChange(e) {
    this.setData({ cropName: this.data.cropOptions[e.detail.value] })
  },

  onStageChange(e) {
    const index = e.detail.value
    this.setData({
      growthStageIndex: index,
      growthStage: this.data.growthStageOptions[index]
    })
  },

  selectUrgency(e) {
    this.setData({ urgency: e.currentTarget.dataset.urgency })
  },

  // ============ AI 帮我整理 ============
  /**
   * 把农户的口语原话交给后端「智能受理」，回填成一张规范工单草稿。
   * 接口 POST /api/farm-reports/ai-parse 只产出草稿、不写库，
   * 所以这里同样只回填表单 —— 提不提交仍然由农户自己点按钮决定。
   */
  aiPolish() {
    if (this.data.parsing) return

    // 描述优先；描述没写但标题写了，就拿标题去整理
    var raw = (this.data.description || '').trim() || (this.data.title || '').trim()
    if (!raw) {
      wx.showToast({ title: '先说说田里什么情况', icon: 'none' })
      return
    }

    this.setData({ parsing: true })
    var villageCode = (app.globalData && app.globalData.villageCode) || 'baini'

    post('/api/farm-reports/ai-parse', { rawText: raw, villageCode: villageCode })
      .then((res) => {
        if (!res || !res.success || !res.data) {
          wx.showToast({ title: (res && res.message) || '整理失败，可直接手写', icon: 'none' })
          this.setData({ polishHint: '整理失败，可直接手写上报' })
          return
        }
        this.applyParseResult(res.data)
      })
      .catch((err) => {
        console.error('[AI 整理失败]', err && (err.errMsg || err.message))
        wx.showToast({ title: '网络不通，请稍后再试', icon: 'none' })
        this.setData({ polishHint: '网络不通，可直接手写上报' })
      })
      .finally(() => {
        this.setData({ parsing: false })
      })
  },

  /** 把受理结果回填进表单：越界值一律丢弃，标题/描述按输入框上限截断 */
  applyParseResult(d) {
    var patch = {}
    if (d.title) patch.title = d.title.slice(0, 50)
    if (d.description) patch.description = d.description.slice(0, 500)
    if (this.data.reportTypes.indexOf(d.reportType) >= 0) patch.reportType = d.reportType
    if (this.data.urgencyOptions.indexOf(d.urgency) >= 0) patch.urgency = d.urgency

    // 作物：模型可能给出选项外的名字，匹配不上就追加进选项再选中，别把识别结果丢掉
    if (d.cropName) {
      var options = this.data.cropOptions
      if (options.indexOf(d.cropName) < 0) options = options.concat([d.cropName])
      patch.cropOptions = options
      patch.cropName = d.cropName
    }

    patch.aiSuggestion = d.suggestedAction || ''
    // aiParsed=false 表示后端走了降级兜底，没真调模型 —— 如实告诉用户
    patch.polishHint = d.aiParsed
      ? '已整理成规范工单，可继续修改'
      : 'AI 暂不可用，已按你的原话填写'
    this.setData(patch)

    if (d.aiParsed) {
      wx.showToast({ title: '已整理，请核对后再提交', icon: 'none' })
    }
  },

  foldAiSuggestion() {
    this.setData({ aiSuggestion: '' })
  },

  chooseImage() {
    wx.chooseImage({
      count: 4 - this.data.imageList.length,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        this.setData({
          imageList: this.data.imageList.concat(res.tempFilePaths)
        })
      },
      fail: (err) => {
        // 用户主动取消选择，不提示错误
        if (err.errMsg && err.errMsg.indexOf('cancel') !== -1) {
          return
        }
        wx.showToast({ title: '选择图片失败，请检查相机/相册权限', icon: 'none' })
      }
    })
  },

  removeImage(e) {
    var index = e.currentTarget.dataset.index
    var list = this.data.imageList
    list.splice(index, 1)
    this.setData({ imageList: list })
  },

  submitReport() {
    if (!this.data.title.trim()) {
      wx.showToast({ title: '请输入标题', icon: 'none' })
      return
    }
    if (!this.data.description.trim()) {
      wx.showToast({ title: '请输入描述', icon: 'none' })
      return
    }

    var openid = wx.getStorageSync('openid')
    if (!openid) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      return
    }

    this.setData({ submitting: true })

    // 先上传图片，再提交上报
    this.uploadImagesAndSubmit()
  },

  // 上传所有图片到服务器，返回服务器URL列表
  uploadImagesAndSubmit() {
    var imageList = this.data.imageList
    if (imageList.length === 0) {
      this.doSubmitReport([])
      return
    }

    wx.showLoading({ title: '上传图片中...' })
    var uploadTasks = imageList.map((filePath) => {
      return this.uploadSingleImage(filePath)
    })

    Promise.all(uploadTasks).then((urls) => {
      wx.hideLoading()
      this.doSubmitReport(urls)
    }).catch((err) => {
      wx.hideLoading()
      console.error('图片上传失败:', err)
      wx.showToast({ title: '图片上传失败，请重试', icon: 'none' })
      this.setData({ submitting: false })
    })
  },

  // 上传单张图片，支持 token 过期后静默重登录再重传一次
  uploadSingleImage(filePath, isRetry) {
    return new Promise((resolve, reject) => {
      var token = wx.getStorageSync('token')
      wx.uploadFile({
        url: BASE_URL + '/api/farm-reports/upload-image',
        filePath: filePath,
        name: 'file',
        header: {
          'Authorization': 'Bearer ' + token
        },
        success: (res) => {
          if (res.statusCode === 200) {
            try {
              var result = JSON.parse(res.data)
              if (result.success && result.data && result.data.url) {
                resolve(result.data.url)
              } else {
                reject(new Error(result.message || '上传失败'))
              }
            } catch (e) {
              reject(new Error('解析响应失败'))
            }
          } else if ((res.statusCode === 401 || res.statusCode === 403) && !isRetry) {
            // token 过期：静默重新登录后重传一次
            relogin()
              .then(() => {
                this.uploadSingleImage(filePath, true).then(resolve, reject)
              })
              .catch(() => reject(new Error('登录已过期，请重新登录')))
          } else {
            reject(new Error('上传失败(' + res.statusCode + ')'))
          }
        },
        fail: (err) => {
          reject(err)
        }
      })
    })
  },

  doSubmitReport(imageUrls) {
    var openid = wx.getStorageSync('openid')
    var userInfo = wx.getStorageSync('userInfo')
    var data = {
      openid: openid,
      nickname: userInfo ? userInfo.nickname : '',
      avatarUrl: userInfo ? userInfo.avatarUrl : '',
      villageCode: app.globalData.villageCode || 'baini',
      villageName: app.globalData.villageName || '白坭镇',
      reportType: this.data.reportType,
      title: this.data.title,
      description: this.data.description,
      imageUrls: imageUrls.join(','),
      cropName: this.data.cropName,
      growthStage: this.data.growthStage,
      urgency: this.data.urgency
    }

    request({
      url: '/api/farm-reports',
      method: 'POST',
      data: data
    }).then(res => {
      if (res.success) {
        wx.showToast({ title: '上报成功', icon: 'success' })
        setTimeout(function() {
          wx.navigateBack()
        }, 1500)
      } else {
        wx.showToast({ title: res.message || '上报失败', icon: 'none' })
      }
    }).catch(() => {
      wx.showToast({ title: '网络错误', icon: 'none' })
    }).finally(() => {
      this.setData({ submitting: false })
    })
  }
})

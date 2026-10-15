/**
 * 小程序 API 地址（局域网直连）
 *
 * 电脑和手机必须在同一个 WiFi 下，且电脑防火墙放行 8082 入站。
 * 写法是「不带 /api、不带结尾斜杠」，代码里会自己拼 /api/xxx。
 * ⚠️ 电脑 IP 变了要改这里（cmd 执行 ipconfig，看 WLAN 那一行的 IPv4）。
 * ⚠️ 改动地址后必须重新编译小程序才会生效。
 * 以后部署到云服务器，就把这里换成服务器的 https 域名（并在小程序后台配好合法域名）。
 */
const LAN_ORIGIN = 'http://192.168.0.100:8082'

const ORIGIN = LAN_ORIGIN

const ENV_URLS = {
  develop: ORIGIN, // 开发者工具模拟器 / 预览 / 真机调试
  trial: ORIGIN,   // 体验版
  release: ORIGIN, // 正式版（上线后换成自己的 https 域名）
}

function getBaseUrl() {
  try {
    const accountInfo = wx.getAccountInfoSync()
    const env = accountInfo.miniProgram.envVersion
    return ENV_URLS[env] || ENV_URLS.develop
  } catch (e) {
    return ENV_URLS.develop
  }
}

const BASE_URL = getBaseUrl()

// ========== token 过期自愈：静默重新登录（并发请求共享同一次登录） ==========
let reloginPromise = null

/**
 * 用 wx.login 换新 code 重新走后端登录，刷新本地 token/openid。
 * 多个请求同时收到 401/403 时只发起一次登录，其余共享等待。
 * @returns {Promise<string>} 新 token
 */
function relogin() {
  if (!reloginPromise) {
    reloginPromise = new Promise((resolve, reject) => {
      wx.login({
        success: (loginRes) => {
          if (!loginRes.code) {
            reject(new Error('wx.login 未返回 code'))
            return
          }
          wx.request({
            url: BASE_URL + '/api/wechat/login',
            method: 'POST',
            data: { code: loginRes.code },
            header: { 'Content-Type': 'application/json' },
            success: (res) => {
              if (res.statusCode === 200 && res.data && res.data.success) {
                const data = res.data.data
                wx.setStorageSync('token', data.token)
                wx.setStorageSync('openid', data.openid)
                // 同步全局状态（userInfo 展示字段不变，避免相对路径拼接问题）
                try {
                  const app = getApp()
                  if (app && app.globalData) {
                    app.globalData.token = data.token
                  }
                } catch (e) { /* getApp 不可用时忽略 */ }
                resolve(data.token)
              } else {
                reject(new Error((res.data && res.data.message) || '重新登录失败'))
              }
            },
            fail: (err) => reject(err)
          })
        },
        fail: (err) => reject(err)
      })
    }).finally(() => { reloginPromise = null })
  }
  return reloginPromise
}

const request = (options, isRetry) => {
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('token')
    const fullUrl = BASE_URL + options.url
    wx.request({
      url: fullUrl,
      method: options.method || 'GET',
      data: options.data || {},
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...options.header
      },
      success: (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
          return
        }
        // 401/403：token 缺失或过期 → 静默重新登录后重试原请求一次（登录接口本身除外，防死循环）
        const needsAuth = res.statusCode === 401 || res.statusCode === 403
        if (needsAuth && !isRetry && options.url !== '/api/wechat/login') {
          relogin()
            .then(() => request(options, true).then(resolve, reject))
            .catch(() => reject(res))
          return
        }
        console.error('[接口异常]', res.statusCode, fullUrl, (res.data && res.data.message) || '')
        reject(res)
      },
      fail: (err) => {
        // 真机排查的关键日志：域名校验被拦 / DNS 失败 / 超时 都会在这里体现
        console.error('[请求失败]', fullUrl, '→', err && err.errMsg)
        reject(err)
      }
    })
  })
}

const get = (url, data) => request({ url, method: 'GET', data })
const post = (url, data) => request({ url, method: 'POST', data })
const put = (url, data) => request({ url, method: 'PUT', data })

module.exports = { request, get, post, put, relogin, BASE_URL }

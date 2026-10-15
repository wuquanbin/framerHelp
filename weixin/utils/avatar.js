/**
 * 头像工具。
 *
 * 重要：默认头像必须用小程序本地图片（/images/avatar-default.png）。
 * 之前用的是 images.unsplash.com 外链 —— 开发者工具勾了「不校验合法域名」能显示，
 * 真机（预览/体验版）因未配置 downloadFile 合法域名 + http 明文限制，必然加载失败。
 * 本地图随代码包分发，任何环境都能显示。
 */
const LOCAL_DEFAULT_AVATAR = '/images/avatar-default.png'

// 兼容旧代码的导出名（原来指向 unsplash 男女头像，现统一收敛为本地默认头像）
const DEFAULT_MALE_AVATAR = LOCAL_DEFAULT_AVATAR
const DEFAULT_FEMALE_AVATAR = LOCAL_DEFAULT_AVATAR

function getAvatarByNickname(nickname, avatarUrl) {
  if (avatarUrl) return avatarUrl
  return LOCAL_DEFAULT_AVATAR
}

module.exports = {
  LOCAL_DEFAULT_AVATAR,
  DEFAULT_MALE_AVATAR,
  DEFAULT_FEMALE_AVATAR,
  getAvatarByNickname
}

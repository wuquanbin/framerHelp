const { request } = require('./request')

/**
 * 加载当前登录用户的个人种植面积（user_crop 表）。
 * <p>
 * 小程序端统一只显示「用户自己的面积」，不再回退到作物档案
 * （crop_profile.plantingArea，村/镇级总体数据）。后者只由后台管理界面展示。
 * </p>
 *
 * @returns {Promise<Object>} { 作物名: 面积(亩) }；未登录或接口失败返回空对象，页面按「面积未设置」处理
 */
function loadCropAreas() {
  return request({ url: '/api/wechat/crop-areas' })
    .then(res => (res && res.success && res.data ? res.data : {}))
    .catch(() => ({}))
}

module.exports = { loadCropAreas }

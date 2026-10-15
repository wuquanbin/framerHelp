/**
 * 接口地址集中配置（部署到云服务器时只需要改 .env.production，不要改代码）
 *
 * - 开发环境：.env.development → VITE_API_ORIGIN=http://localhost:8082
 * - 生产环境：.env.production  → VITE_API_ORIGIN 留空，走同源请求
 *   （由 nginx 把 /api 和 /uploads 反向代理到后端的 8082 端口，
 *   这样不用写死服务器域名，也不存在跨域问题）
 */
const envOrigin = import.meta.env.VITE_API_ORIGIN as string | undefined

/** 后端服务地址，例如 http://localhost:8082；生产环境为空串表示同源 */
export const API_ORIGIN = envOrigin !== undefined ? envOrigin : 'http://localhost:8082'

/** REST 接口基址，例如 /api */
export const API_BASE = `${API_ORIGIN}/api`

/** 静态文件（头像、上报图片）基址，后端 /uploads/** 直接对外提供 */
export const FILE_BASE = API_ORIGIN

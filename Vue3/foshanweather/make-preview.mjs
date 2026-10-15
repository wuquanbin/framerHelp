// 生成带真实登录态的预览页，便于无交互截图后台
import fs from 'node:fs'
import path from 'node:path'

const dist = path.resolve('dist')

const res = await fetch('http://localhost:8082/api/admin/login', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: 'admin', password: 'admin123' }),
})
const payload = await res.json()
if (!payload.success) throw new Error('登录失败：' + payload.message)
const user = payload.data

const seed =
  '<script>localStorage.setItem("admin_token",' +
  JSON.stringify(user.token) +
  ');localStorage.setItem("admin_user",' +
  JSON.stringify(JSON.stringify(user)) +
  ');</script>'

const html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8')
fs.writeFileSync(path.join(dist, '__preview.html'), html.replace('<head>', '<head>' + seed))
console.log('__preview.html 已生成，账号：' + user.username + ' / ' + user.realName)

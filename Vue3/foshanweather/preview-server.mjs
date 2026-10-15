import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve('dist')
const types = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.jpg': 'image/jpeg',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
}

http
  .createServer((req, res) => {
    const url = decodeURIComponent((req.url || '/').split('?')[0])
    const rel = url === '/' ? '/index.html' : url
    const file = path.join(root, rel)
    if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      // SPA 回退：带登录态种子，方便无后端时直接预览后台
      const fallback = path.join(root, '__preview.html')
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      res.end(fs.readFileSync(fs.existsSync(fallback) ? fallback : path.join(root, 'index.html')))
      return
    }
    res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' })
    res.end(fs.readFileSync(file))
  })
  .listen(5199, () => console.log('preview on http://127.0.0.1:5199'))

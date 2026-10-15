import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { adminLogin, getAdminInfo } from '@/api/admin'

export const useAdminStore = defineStore('admin', () => {
  const token = ref(localStorage.getItem('admin_token') || '')
  const username = ref('')
  const realName = ref('')
  const role = ref('')
  /** 数据范围：村镇管理员绑定的村镇编码，其余角色为空 */
  const villageCode = ref('')

  const isLoggedIn = computed(() => !!token.value)

  async function login(user: string, password: string) {
    const result = await adminLogin(user, password)
    token.value = result.token
    username.value = result.username
    realName.value = result.realName
    role.value = result.role
    villageCode.value = result.villageCode || ''
    localStorage.setItem('admin_token', result.token)
    localStorage.setItem('admin_user', JSON.stringify(result))
  }

  async function loadInfo() {
    try {
      const info = await getAdminInfo()
      username.value = info.username
      realName.value = info.realName
      role.value = info.role
      villageCode.value = info.villageCode || ''
    } catch {
      logout()
    }
  }

  function logout() {
    token.value = ''
    username.value = ''
    realName.value = ''
    role.value = ''
    villageCode.value = ''
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_user')
  }

  function restoreSession() {
    const saved = localStorage.getItem('admin_user')
    if (saved) {
      try {
        const parsed = JSON.parse(saved)
        username.value = parsed.username || ''
        realName.value = parsed.realName || ''
        role.value = parsed.role || ''
        villageCode.value = parsed.villageCode || ''
      } catch { /* ignore */ }
    }
  }

  restoreSession()

  return { token, username, realName, role, villageCode, isLoggedIn, login, loadInfo, logout }
})
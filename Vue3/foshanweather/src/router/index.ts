import { createRouter, createWebHistory } from 'vue-router'
import { canManageSystem } from '@/utils/roles'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/admin/dashboard',
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
    },
    {
      path: '/admin',
      component: () => import('@/views/AdminLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/admin/dashboard' },
        { path: 'dashboard', name: 'admin-dashboard', component: () => import('@/views/admin/DashboardView.vue'), meta: { immersive: true } },
        { path: 'villages', name: 'admin-villages', component: () => import('@/views/admin/VillageManage.vue') },
        { path: 'crops', name: 'admin-crops', component: () => import('@/views/admin/CropManage.vue') },
        { path: 'farm-advices', name: 'admin-farm-advices', component: () => import('@/views/admin/FarmAdviceManage.vue') },
        { path: 'disasters', name: 'admin-disasters', component: () => import('@/views/admin/DisasterManage.vue') },
        { path: 'assistance-records', name: 'admin-assistance-records', component: () => import('@/views/admin/AssistanceManage.vue') },
        { path: 'assistance-dashboard', name: 'admin-assistance-dashboard', component: () => import('@/views/admin/AssistanceDashboard.vue') },
        { path: 'farm-reports', name: 'admin-farm-reports', component: () => import('@/views/admin/FarmReportManage.vue') },
        { path: 'ai-chat', name: 'admin-ai-chat', component: () => import('@/views/admin/AiChatView.vue') },
        { path: 'human-chat', name: 'admin-human-chat', component: () => import('@/views/admin/HumanChatWorkbench.vue') },
        // 人员管理与系统设置只有超级管理员可进；操作日志对专家放开。
        // 菜单里同样过滤过一次 —— 这里是防「手动敲地址」绕过菜单。
        { path: 'users', name: 'admin-users', component: () => import('@/views/admin/UserManage.vue'), meta: { roles: ['SUPER_ADMIN'] } },
        { path: 'feedback', name: 'admin-feedback', component: () => import('@/views/admin/FeedbackManage.vue') },
        { path: 'logs', name: 'admin-logs', component: () => import('@/views/admin/OperationLogView.vue'), meta: { roles: ['SUPER_ADMIN', 'EXPERT'] } },
        { path: 'system', name: 'admin-system', component: () => import('@/views/admin/SystemManage.vue'), meta: { roles: ['SUPER_ADMIN'] } },
        // 提示词模板与系统参数同级：写接口只放行 SUPER_ADMIN，这里挡住手动敲地址
        { path: 'prompt-templates', name: 'admin-prompt-templates', component: () => import('@/views/admin/PromptTemplateManage.vue'), meta: { roles: ['SUPER_ADMIN', 'EXPERT'] } },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('admin_token')
  if (to.meta.requiresAuth && !token) {
    return { name: 'login' }
  }

  // 角色守卫：meta.roles 声明了该页面允许的角色。
  // 注意这只是「别让用户看到 403」，真正的拦截在后端 SecurityConfig ——
  // 接口是可以直接被 curl 打到的，前端拦不住。
  const roles = to.meta.roles as string[] | undefined
  if (roles && roles.length > 0 && token) {
    const saved = localStorage.getItem('admin_user')
    const role = saved ? JSON.parse(saved).role : ''
    if (!roles.includes(role)) {
      return { name: 'admin-dashboard' }
    }
  }
})

export default router

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAdminStore } from '@/stores/admin'
import { useHumanChatStore } from '@/stores/humanChat'
import { useNoticeStore } from '@/stores/notice'
import { useSkyStore } from '@/stores/sky'
import { ElMessageBox, ElNotification } from 'element-plus'
import { adminChatWs } from '@/utils/adminChatWs'
import { hasMenuAccess, roleLabel as roleText, normalizeRole } from '@/utils/roles'
import type { AdminRole } from '@/utils/roles'

interface MenuItem {
  path: string
  title: string
  /** 可见角色；不填 = 所有已登录角色可见 */
  roles?: AdminRole[]
}
interface MenuGroup {
  name: string
  icon: string
  items: MenuItem[]
}

const router = useRouter()
const route = useRoute()
const adminStore = useAdminStore()
const humanChatStore = useHumanChatStore()
const noticeStore = useNoticeStore()
const skyStore = useSkyStore()

/**
 * 菜单按「上班要处理的几类事」分五组：看板 / 待办 / 档案 / 服务 / 系统。
 * 组名两字、条目一律四字（面板宽固定 190px），且每组第一项是该组主入口 —— 轨道图标单击直达它。
 */
const menuGroups: MenuGroup[] = [
  {
    name: '看板',
    icon: 'DataBoard',
    items: [
      { path: '/admin/dashboard', title: '数据概览' },
      { path: '/admin/assistance-dashboard', title: '帮扶成效' },
    ],
  },
  {
    name: '待办',
    icon: 'Bell',
    items: [
      { path: '/admin/farm-reports', title: '农情上报' },
      { path: '/admin/human-chat', title: '人工客服' },
      { path: '/admin/feedback', title: '农户反馈' },
    ],
  },
  {
    name: '档案',
    icon: 'Folder',
    items: [
      { path: '/admin/crops', title: '作物档案' },
      { path: '/admin/villages', title: '村镇档案' },
      { path: '/admin/disasters', title: '灾害记录' },
    ],
  },
  {
    name: '服务',
    icon: 'Service',
    items: [
      { path: '/admin/farm-advices', title: '农事建议' },
      { path: '/admin/assistance-records', title: '帮扶台账' },
      { path: '/admin/ai-chat', title: 'AI指挥台' },
    ],
  },
  {
    name: '系统',
    icon: 'Setting',
    items: [
      // 以下四项按后端 SecurityConfig 的权限一一对应：
      // /api/admin/admins、/config、operation-logs 仅超管，操作日志额外对专家放开。
      // 提示词模板与系统设置同组同权限：它决定 AI 的回答口径，属于系统级配置。
      { path: '/admin/system', title: '系统设置', roles: ['SUPER_ADMIN'] },
      { path: '/admin/users', title: '后台账号', roles: ['SUPER_ADMIN'] },
      { path: '/admin/logs', title: '操作日志', roles: ['SUPER_ADMIN', 'EXPERT'] },
      { path: '/admin/prompt-templates', title: '提示词模板', roles: ['SUPER_ADMIN', 'EXPERT'] },
    ],
  },
]

/**
 * 按当前角色过滤后的菜单。
 * 过滤而不是简单隐藏：分组里所有条目都不可见时整组消失，
 * 否则会留下一个点开是空的图标轨道项。
 */
const visibleMenuGroups = computed(() =>
  menuGroups
    .map((g) => ({ ...g, items: g.items.filter((i) => hasMenuAccess(adminStore.role, i.roles)) }))
    .filter((g) => g.items.length > 0),
)

const allMenuItems = computed(() => visibleMenuGroups.value.flatMap((g) => g.items))

/** 当前路由所在的分组 */
const activeGroup = computed(
  () => visibleMenuGroups.value.find((g) => g.items.some((i) => i.path === route.path)) ?? null,
)
const currentTitle = computed(() => allMenuItems.value.find((m) => m.path === route.path)?.title ?? '')
const activeGroupName = computed(() => activeGroup.value?.name ?? '')

/** 疏密均匀的侧栏徽标 */
function badgeOf(path: string): string {
  if (path === '/admin/human-chat') {
    if (humanChatStore.waitingCount > 0) return 'NEW'
    if (humanChatStore.totalUnread > 0) return String(humanChatStore.totalUnread)
    return ''
  }
  if (path === '/admin/feedback') {
    return noticeStore.pendingFeedback > 0 ? String(noticeStore.pendingFeedback) : ''
  }
  if (path === '/admin/farm-reports') {
    return noticeStore.pendingFarmReports > 0 ? String(noticeStore.pendingFarmReports) : ''
  }
  return ''
}

/** 分组角标：组内各项待办数加总（'NEW' 这类非数字文案记作 1）。 */
function groupBadgeCount(group: MenuGroup): number {
  return group.items.reduce((sum, i) => {
    const text = badgeOf(i.path)
    if (!text) return sum
    const n = Number(text)
    return sum + (Number.isNaN(n) ? 1 : n)
  }, 0)
}

// ============ 悬浮二级面板 ============
const flyIndex = ref(-1)
const flyTop = ref(0)
let closeTimer: number | undefined

const flyGroup = computed(() => (flyIndex.value >= 0 ? visibleMenuGroups.value[flyIndex.value] : null))

function flyHeightOf(count: number): number {
  return 58 + count * 46
}

function openFly(index: number): void {
  if (closeTimer) {
    window.clearTimeout(closeTimer)
    closeTimer = undefined
  }
  const group = visibleMenuGroups.value[index]
  // 菜单现在是过滤后的数组，索引可能越界（角色切换的瞬间尤其容易），先挡一道
  if (!group) {
    flyIndex.value = -1
    return
  }
  if (group.items.length <= 1) {
    flyIndex.value = -1
    return
  }
  flyIndex.value = index
  const ideal = 68 + index * 52
  const max = window.innerHeight - flyHeightOf(group.items.length) - 16
  flyTop.value = Math.max(16, Math.min(ideal, max))
}

function scheduleClose(): void {
  if (closeTimer) window.clearTimeout(closeTimer)
  closeTimer = window.setTimeout(() => {
    flyIndex.value = -1
  }, 160)
}

function cancelClose(): void {
  if (closeTimer) {
    window.clearTimeout(closeTimer)
    closeTimer = undefined
  }
}

/**
 * 单击轨道图标 = 直接进该组的主入口（第一项），悬浮才展开选其余项。
 * 旧逻辑只在「单项分组」时直达，现在概览组有两项（数据概览 + AI指挥台）——
 * 数据概览是每天第一个打开的页面，不该因此变成两次点击。
 * 各组第一项都刻意选成了该组主入口，所以统一直达是安全的。
 */
function onRailClick(group: MenuGroup): void {
  const first = group.items[0]
  if (!first) return
  flyIndex.value = -1
  router.push(first.path)
}

function go(path: string): void {
  flyIndex.value = -1
  router.push(path)
}

// ============ 沉浸式页面（数据概览走全幅背景） ============
const isImmersive = computed(() => route.meta.immersive === true)

async function handleLogout() {
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' })
  adminStore.logout()
  adminChatWs.close()
  router.push('/login')
}

let unsubNotify: (() => void) | null = null

onMounted(() => {
  noticeStore.startAutoRefresh()
  adminChatWs.connect()
  humanChatStore.init()
  humanChatStore.fetchSummary()
  skyStore.startAutoRefresh()
  unsubNotify = adminChatWs.on('NEW_SESSION_NOTIFY', (msg) => {
    if (route.path === '/admin/human-chat') return
    const farmerName = msg.body?.session?.farmerNickname || '一位农民'
    ElNotification({
      title: '收到新的农民求助',
      message: `${farmerName} 发起了人工客服请求，请前往「人工客服工作台」处理`,
      type: 'warning',
      duration: 8000,
    })
  })
})

onUnmounted(() => {
  unsubNotify?.()
  noticeStore.stopAutoRefresh()
  skyStore.stopAutoRefresh()
  if (closeTimer) window.clearTimeout(closeTimer)
})
</script>

<template>
  <div class="ad-shell" :data-sky="skyStore.state" :data-precip="skyStore.precipitating ? '1' : '0'">
    <!-- 共享天象背景 -->
    <div class="ad-bg" aria-hidden="true">
      <div class="ad-photo p-storm"></div>
      <div class="ad-photo p-rain"></div>
      <div class="ad-photo p-sunny"></div>
      <div class="ad-photo p-alert"></div>
      <div class="ad-clouds"></div>
      <div class="ad-rain"></div>
      <div class="ad-wash"></div>
    </div>

    <div class="ad-body">
      <!-- 70px 图标轨道 -->
      <nav class="ad-rail" @mouseleave="scheduleClose">
        <div class="ad-mark">农</div>

        <button
          v-for="(group, i) in visibleMenuGroups"
          :key="group.name"
          class="ad-rail-item"
          :class="{ on: activeGroupName === group.name, 'panel-open': flyIndex === i }"
          type="button"
          @mouseenter="openFly(i)"
          @click="onRailClick(group)"
        >
          <el-icon><component :is="group.icon" /></el-icon>
          <em v-if="groupBadgeCount(group) > 0" class="ad-rail-badge">
            {{ groupBadgeCount(group) > 99 ? '99+' : groupBadgeCount(group) }}
          </em>
          <!-- 分组名提示：面板打开时由 .panel-open 规则隐藏，避免和面板标题重复 -->
          <span class="ad-rail-tip">{{ group.name }}</span>
        </button>

        <span class="ad-rail-spacer"></span>

        <button class="ad-rail-item" type="button" title="退出登录" @click="handleLogout" @mouseenter="flyIndex = -1">
          <el-icon><SwitchButton /></el-icon>
        </button>
      </nav>

      <!-- 悬浮二级面板 -->
      <Transition name="ad-fly">
        <aside
          v-if="flyGroup"
          class="ad-fly"
          :style="{ top: flyTop + 'px' }"
          @mouseenter="cancelClose"
          @mouseleave="scheduleClose"
        >
          <header class="ad-fly-head">{{ flyGroup.name }}</header>
          <button
            v-for="item in flyGroup.items"
            :key="item.path"
            class="ad-fly-item"
            :class="{ on: route.path === item.path }"
            type="button"
            @click="go(item.path)"
          >
            <i class="ad-fly-dot"></i>
            <span class="ad-fly-title">{{ item.title }}</span>
            <em v-if="badgeOf(item.path)" class="ad-fly-badge">{{ badgeOf(item.path) }}</em>
          </button>
        </aside>
      </Transition>

      <!-- 内容区 -->
      <section class="ad-main" :class="{ immersive: isImmersive }">
        <header v-if="!isImmersive" class="ad-top">
          <nav class="ad-crumb">
            <span class="ad-crumb-root">乡村农业气象帮扶</span>
            <span class="ad-crumb-sep">/</span>
            <span class="ad-crumb-cur">{{ currentTitle }}</span>
          </nav>

          <div class="ad-top-right">
            <span class="ad-top-chip">
              <i class="ad-chip-dot"></i>
              {{ skyStore.ready ? `${skyStore.villageName || '全域'} · ${skyStore.weatherText}` : '天象加载中' }}
            </span>
            <span class="ad-top-user">
              <i class="ad-avatar">{{ (adminStore.realName || adminStore.username || '管').slice(0, 1) }}</i>
              {{ adminStore.realName || adminStore.username }}
              <!-- 角色徽标：当前登录身份要一直看得见，否则演示时说不清「现在是谁在操作」 -->
              <em class="ad-role-badge" :class="'is-' + normalizeRole(adminStore.role).toLowerCase()">
                {{ roleText(adminStore.role) }}
              </em>
            </span>
            <button class="ad-top-btn" type="button" @click="handleLogout">退出</button>
          </div>
        </header>

        <div class="ad-view">
          <!-- 不用 <Transition mode="out-in">：天象/角标轮询回包引发的外壳重渲染
               若落在 leave 过渡窗口，Transition 内部状态会永久卡住、内容区空白。
               按 route.path 重建 + CSS 进入动画没有过渡状态机，不存在该竞态。 -->
          <!-- 动画挂在包裹节点而非页面组件：部分页面是多根（fragment/teleport）模板，
               class 无法继承到组件根，会触发 "Extraneous non-props attributes" 警告。 -->
          <router-view v-slot="{ Component }">
            <div :key="route.path" class="ad-page">
              <component :is="Component" />
            </div>
          </router-view>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ============ 天象主题 token ============ */
.ad-shell {
  --ad-accent: #eaf3ea;
  --ad-accent-dim: rgba(234, 243, 234, 0.7);
  --ad-accent-on: #12301f;
  --ad-g1: rgba(255, 255, 255, 0.4);
  --ad-g2: rgba(255, 255, 255, 0.17);
  --ad-gbd: rgba(255, 255, 255, 0.36);
  --ad-shield: rgba(255, 255, 255, 0.16);
  --ad-scrim-1: rgba(4, 10, 14, 0.54);
  --ad-scrim-2: rgba(4, 10, 14, 0.38);
  --ad-scrim-3: rgba(0, 0, 0, 0.52);
  --ad-rain-o: 0;
  --ad-star-o: 1;
  --ad-panel: rgba(251, 252, 251, 0.93);
  /* 非沉浸式页面的可用内容高度：视口 − 外壳内边距 − 顶栏 − 内容区内边距。
     页面内的 calc(100vh - Npx) 请改用此变量，避免出现多余滚动条。 */
  --ad-view-h: calc(100vh - 128px);
}
.ad-shell[data-sky='rain'] {
  --ad-accent: #eef5f2;
  --ad-accent-dim: rgba(238, 245, 242, 0.7);
  --ad-accent-on: #12211c;
  --ad-g1: rgba(255, 255, 255, 0.36);
  --ad-g2: rgba(255, 255, 255, 0.14);
  --ad-gbd: rgba(255, 255, 255, 0.31);
  --ad-scrim-1: rgba(10, 18, 22, 0.48);
  --ad-scrim-2: rgba(10, 18, 22, 0.36);
  --ad-scrim-3: rgba(0, 0, 0, 0.5);
  --ad-rain-o: 1;
  --ad-star-o: 0;
}
.ad-shell[data-sky='sunny'] {
  --ad-accent: #f1f8f2;
  --ad-accent-dim: rgba(241, 248, 242, 0.76);
  --ad-accent-on: #12301f;
  --ad-g1: rgba(255, 255, 255, 0.37);
  --ad-g2: rgba(255, 255, 255, 0.15);
  --ad-gbd: rgba(255, 255, 255, 0.33);
  --ad-scrim-1: rgba(6, 22, 36, 0.42);
  --ad-scrim-2: rgba(6, 22, 36, 0.38);
  --ad-scrim-3: rgba(0, 0, 0, 0.4);
  --ad-rain-o: 0;
  --ad-star-o: 0;
}
.ad-shell[data-sky='alert'] {
  --ad-accent: #fff2dd;
  --ad-accent-dim: rgba(255, 242, 221, 0.72);
  --ad-accent-on: #3a1f08;
  --ad-g1: rgba(255, 255, 255, 0.36);
  --ad-g2: rgba(255, 255, 255, 0.14);
  --ad-gbd: rgba(255, 255, 255, 0.32);
  --ad-scrim-1: rgba(30, 12, 2, 0.46);
  --ad-scrim-2: rgba(30, 12, 2, 0.34);
  --ad-scrim-3: rgba(0, 0, 0, 0.44);
  --ad-rain-o: 0;
  --ad-star-o: 1;
}

.ad-shell {
  position: relative;
  height: 100vh;
  overflow: hidden;
}

/* ============ 共享背景层 ============ */
.ad-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #0d1a20 0%, #1c3a35 55%, #2f5c22 100%);
}
.ad-photo {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  opacity: 0;
  transition: opacity 1.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.p-storm {
  background-image: url('/weather-bg/bg-storm.jpg');
}
.p-rain {
  background-image: url('/weather-bg/bg-rain.jpg');
}
.p-sunny {
  background-image: url('/weather-bg/bg-sunny.jpg');
  filter: brightness(0.66) saturate(1.06);
}
.p-alert {
  background-image: url('/weather-bg/bg-alert.jpg');
}
.ad-shell[data-sky='storm'] .p-storm,
.ad-shell[data-sky='rain'] .p-rain,
.ad-shell[data-sky='sunny'] .p-sunny,
.ad-shell[data-sky='alert'] .p-alert {
  opacity: 1;
}

/* 缓慢漂移的雾气，给静态照片保留"天在动"的感觉 */
.ad-clouds {
  position: absolute;
  inset: -10%;
  background:
    radial-gradient(38% 26% at 22% 26%, rgba(255, 255, 255, 0.14), transparent 70%),
    radial-gradient(30% 22% at 68% 16%, rgba(255, 255, 255, 0.1), transparent 72%),
    radial-gradient(46% 24% at 82% 44%, rgba(255, 255, 255, 0.08), transparent 74%);
  filter: blur(18px);
  animation: ad-drift 68s ease-in-out infinite alternate;
}
@keyframes ad-drift {
  from {
    transform: translate3d(-2%, 0, 0) scale(1);
  }
  to {
    transform: translate3d(3%, -2%, 0) scale(1.06);
  }
}

/* 阴天/多云只是云量多，底图虽为灰蓝阴天，但不该有雨丝在飘。
   只有实况确实在降水（雨/雪/雷/冰雹）时才让雨丝层可见。 */
.ad-shell[data-precip='0'] .ad-rain {
  opacity: 0;
}

.ad-rain {
  position: absolute;
  inset: -20% 0 0;
  opacity: var(--ad-rain-o);
  transition: opacity 1.2s;
  background-image: repeating-linear-gradient(
    102deg,
    rgba(255, 255, 255, 0) 0 9px,
    rgba(255, 255, 255, 0.16) 9px 10px,
    rgba(255, 255, 255, 0) 10px 22px
  );
  animation: ad-fall 1.05s linear infinite;
}
@keyframes ad-fall {
  0% {
    transform: translate3d(0, -14%, 0);
  }
  100% {
    transform: translate3d(-6%, 14%, 0);
  }
}

.ad-wash {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, var(--ad-scrim-1) 0%, transparent 58%),
    linear-gradient(180deg, var(--ad-scrim-2) 0%, transparent 32%, transparent 56%, var(--ad-scrim-3) 100%);
  transition: background 1.2s;
}

/* ============ 布局骨架 ============ */
.ad-body {
  position: relative;
  z-index: 2;
  display: flex;
  height: 100vh;
  padding: 12px;
  gap: 12px;
}

/* 图标轨道
   z-index 不能省：backdrop-filter 会让轨道自身形成层叠上下文，但 z-index:auto 时，
   内容区里 position:relative 的元素（el-table 等）会按 DOM 顺序画到轨道之上，
   把悬停提示 .ad-rail-tip 压在白卡下面。显式给 30，让轨道（含提示）稳定高于内容，
   同时低于 .ad-fly(40)。 */
.ad-rail {
  position: relative;
  z-index: 30;
  flex: 0 0 66px;
  width: 66px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 14px 0 14px;
  gap: 8px;
  border-radius: 20px;
  border: 1px solid var(--ad-gbd);
  background: linear-gradient(160deg, var(--ad-g1), var(--ad-g2));
  /* 只写标准属性：手写 -webkit- 配套行会被构建器的压缩器判定为同一属性，
     从而丢弃标准声明，导致 Chrome 下模糊完全失效。前缀由构建器自动补。 */
  backdrop-filter: blur(26px) saturate(160%);
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.42);
  transition: background 1.2s, border-color 1.2s;
}
.ad-mark {
  width: 40px;
  height: 40px;
  margin-bottom: 6px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(150deg, #4f8b3c, #24562f);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.3);
}
.ad-rail-spacer {
  flex: 1;
}
.ad-rail-item {
  position: relative;
  width: 44px;
  height: 44px;
  border: 0;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  color: var(--ad-accent-dim);
  background: transparent;
  cursor: pointer;
  transition: 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}
.ad-rail-item:hover {
  color: #fff;
  background: var(--ad-shield);
}
.ad-rail-item.on {
  color: var(--ad-accent-on);
  background: var(--ad-accent);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.28);
}
/* 一级图标角标：显示该组待办总数 */
.ad-rail-badge {
  position: absolute;
  top: 4px;
  right: 3px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 9px;
  background: #ff9f43;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  line-height: 17px;
  text-align: center;
  box-shadow: 0 0 0 2px rgba(255, 159, 67, 0.25);
  animation: ad-pulse 2s ease-in-out infinite;
}
@keyframes ad-pulse {
  0%,
  100% {
    opacity: 0.45;
  }
  50% {
    opacity: 1;
  }
}
/* 折叠态的悬浮文字提示 */
.ad-rail-tip {
  position: absolute;
  left: 54px;
  top: 50%;
  transform: translateY(-50%) translateX(-6px);
  padding: 5px 10px;
  border-radius: 9px;
  font-size: 12px;
  font-style: normal;
  white-space: nowrap;
  color: #fff;
  background: rgba(14, 26, 22, 0.82);
  backdrop-filter: blur(12px);
  opacity: 0;
  pointer-events: none;
  transition: 0.24s cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 20;
}
.ad-rail-item:hover .ad-rail-tip {
  opacity: 1;
  transform: translateY(-50%) translateX(0);
}
/* 面板展开时藏掉轨道提示条：它的文字与面板标题重复，露出一截像重复渲染 */
.ad-rail-item.panel-open .ad-rail-tip {
  opacity: 0;
  transform: translateY(-50%) translateX(-6px);
}

/* 悬浮二级面板
   面板会同时压在「暗色照片」和「浅色内容板」两种背景上，
   所以不能用随主题变化的 --ad-* 变量（浅色主题下会变成白字白底），
   这里固定用深色玻璃 + 浅色字，任何背景下都清晰。 */
.ad-fly {
  position: absolute;
  left: 90px;
  z-index: 40;
  width: 190px;
  padding: 10px 10px 8px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(16, 26, 22, 0.84);
  backdrop-filter: blur(22px) saturate(150%);
  box-shadow: 0 22px 52px rgba(0, 0, 0, 0.42), inset 0 1px 0 rgba(255, 255, 255, 0.14);
  color: #eef4f0;
}
.ad-fly-head {
  padding: 4px 8px 8px;
  margin-bottom: 6px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 11px;
  letter-spacing: 0.1em;
  color: rgba(238, 244, 240, 0.72);
}
.ad-fly-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 12px 10px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: #eef4f0;
  font: inherit;
  font-size: 13.5px;
  text-align: left;
  cursor: pointer;
  transition: 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}
.ad-fly-item:hover {
  background: rgba(255, 255, 255, 0.13);
  transform: translateX(2px);
}
.ad-fly-item.on {
  background: rgba(255, 255, 255, 0.95);
  color: #12301f;
}
.ad-fly-item.on .ad-fly-dot {
  opacity: 0.7;
}
.ad-fly-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.45;
  flex: 0 0 5px;
}
.ad-fly-title {
  flex: 1;
}
/* 待办角标用琥珀色（与轨道一致）：红色在本系统是「灾害 / 风险等级」的语义色。 */
.ad-fly-badge {
  min-width: 18px;
  height: 17px;
  padding: 0 5px;
  border-radius: 9px;
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  line-height: 17px;
  text-align: center;
  background: #ff9f43;
  color: #fff;
}
.ad-fly-enter-active,
.ad-fly-leave-active {
  transition: opacity 0.22s, transform 0.28s cubic-bezier(0.16, 1, 0.3, 1);
}
.ad-fly-enter-from,
.ad-fly-leave-to {
  opacity: 0;
  transform: translateX(-10px) scale(0.98);
}

/* 内容区：顶栏直接浮在照片上，工作区是一块悬浮的浅色玻璃板，
   这样非沉浸式页面也能看到天象背景，与数据概览页共用同一套视觉语言。 */
.ad-main {
  /* 显式建立 z-index:1 的层叠上下文，把页面内部所有元素（含 el-table 等
     position:relative / 自带 z-index 的组件）统一关在这一层里，
     保证轨道(30)与悬浮二级面板(40)永远浮在内容之上。
     只用 position + z-index，不用 transform/filter，避免把 fixed 弹窗关进来。 */
  position: relative;
  z-index: 1;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
}
.ad-top {
  flex: 0 0 auto;
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 24px;
  color: var(--ad-accent);
  text-shadow: 0 2px 16px rgba(0, 0, 0, 0.52);
}
.ad-crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ad-accent-dim);
}
.ad-crumb-root {
  font-weight: 500;
  color: var(--ad-accent);
}
.ad-crumb-cur {
  color: var(--ad-accent-dim);
}
.ad-top-right {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;
  color: var(--ad-accent-dim);
}
.ad-top-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  background: var(--ad-shield);
  border: 1px solid var(--ad-gbd);
  backdrop-filter: blur(14px);
  color: var(--ad-accent);
  text-shadow: none;
}
.ad-chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: ad-pulse 2.4s ease-in-out infinite;
}
.ad-top-user {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.ad-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-style: normal;
  color: #fff;
  background: linear-gradient(150deg, #c98f4e, #7d4a1c);
}
/* 角色徽标：三级用三种底色区分（超级管理员=陶红、专家=金、村镇=绿） */
.ad-role-badge {
  font-style: normal;
  font-size: 11px;
  line-height: 1;
  padding: 4px 8px;
  border-radius: 999px;
  color: #fff;
  background: #c9a227;
}
.ad-role-badge.is-super_admin {
  background: #b0452b;
}
.ad-role-badge.is-village_admin {
  background: #2d6a4f;
}
.ad-top-btn {
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--ad-gbd);
  background: var(--ad-shield);
  backdrop-filter: blur(14px);
  font: inherit;
  font-size: 13px;
  color: var(--ad-accent);
  cursor: pointer;
  transition: 0.24s;
}
.ad-top-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  color: #fff;
}

.ad-view {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 18px 20px 24px;
  border-radius: 20px 20px 0 0;
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-bottom: 0;
  background: var(--ad-panel);
  /* 不可加 backdrop-filter：非 none 值会使本元素成为 fixed 后代的包含块，
     把 Element Plus 弹窗的 .el-overlay（position:fixed）困在面板内，
     遮罩盖不全、弹窗还会被 overflow:auto 裁切。面板不透明度 0.93，无需模糊。 */
  box-shadow: 0 -10px 44px rgba(0, 0, 0, 0.2);
  transition: background 0.6s ease;
}
.ad-main.immersive .ad-view {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
}
.ad-main.immersive .ad-view::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.26);
}

/* 路由进入动画：靠 :key="route.path" 重建 DOM 触发，只做进入、不做离开，避免切换空窗。 */
.ad-page {
  /* 填充模式用 backwards 不用 both：both 会在动画结束后保留 transform 矩阵，
     而带 transform 的元素是 fixed 后代的包含块，会把弹窗遮罩困在页面区域内；
     backwards 结束后元素无 transform，视觉效果一致。 */
  animation: ad-page-in 0.36s cubic-bezier(0.16, 1, 0.3, 1) backwards;
}
@keyframes ad-page-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* 内容区滚动条 */
.ad-view::-webkit-scrollbar {
  width: 8px;
}
.ad-view::-webkit-scrollbar-thumb {
  border-radius: 4px;
  background: rgba(40, 70, 55, 0.16);
}
.ad-view::-webkit-scrollbar-thumb:hover {
  background: rgba(40, 70, 55, 0.3);
}

@media (max-width: 900px) {
  .ad-body {
    padding: 8px;
    gap: 8px;
  }
  .ad-rail {
    flex: 0 0 56px;
    width: 56px;
    padding: 10px 0;
  }
  .ad-mark {
    width: 34px;
    height: 34px;
    font-size: 15px;
  }
  .ad-rail-item {
    width: 38px;
    height: 38px;
    font-size: 17px;
  }
  .ad-fly {
    left: 70px;
    width: 172px;
  }
  .ad-view {
    padding: 14px;
  }
}
</style>

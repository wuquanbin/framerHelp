<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useAdminStore } from '@/stores/admin'
import { ElMessage } from 'element-plus'
import harvestBg from '@/assets/images/login-harvest.jpg'

/** 路由实例，用于登录成功后跳转 */
const router = useRouter()
/** 管理员状态仓库 */
const adminStore = useAdminStore()

/** 登录表单数据 */
const username = ref('')
const password = ref('')
/** 记住我 / 密码明文显示 / 提交加载态 */
const remember = ref(false)
const showPassword = ref(false)
const loading = ref(false)

/**
 * 天气剧场状态机（单一真相源）
 * idle 晴天待机 | user 聚焦用户名（云开见光） | pwd 聚焦密码（云遮日、细雨）
 * fail 登录失败（阵雨、降饱和） | success 登录成功（雨停、云散、光出）
 */
type Mood = 'idle' | 'user' | 'pwd' | 'fail' | 'success'
const mood = ref<Mood>('idle')

const usernameInputRef = ref<HTMLInputElement | null>(null)
const passwordInputRef = ref<HTMLInputElement | null>(null)
const REMEMBER_KEY = 'admin_remember_username'

/** 字段级 / 表单级错误（内联展示，供屏幕阅读器播报） */
const errors = reactive({ username: '', password: '', form: '' })
/** 登录成功后锁定提交，等待跳转，防止重复请求 */
const submitted = ref(false)
/** 仅供屏幕阅读器的状态播报 */
const liveStatus = ref('')

const reduceMotion =
  typeof window !== 'undefined' &&
  window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** 登录成功后的跳转定时器（卸载时清理，避免切走后仍触发跳转） */
let redirectTimer = 0
/** idle 休眠计时器：8 秒无交互后天气动效落静态，任意交互即唤醒 */
let idleTimer = 0
let lastWake = 0
const dormant = ref(false)
const IDLE_MS = 8000

/* ---------- idle 休眠：任何交互都重新计时，仅在 idle 态超时后休眠 ---------- */
function wake() {
  const now = Date.now()
  const wasDormant = dormant.value
  if (wasDormant) dormant.value = false
  // 高频 mousemove 下节流，每秒最多重置一次；休眠中的第一次交互立即重置
  if (!wasDormant && now - lastWake < 1000) return
  lastWake = now
  window.clearTimeout(idleTimer)
  idleTimer = window.setTimeout(() => {
    if (mood.value === 'idle') dormant.value = true
  }, IDLE_MS)
}

/* ---------- 输入框交互：驱动天气状态 ---------- */
function onUsernameFocus() {
  // fail 态的阵雨要保留到用户重新输入，不能被聚焦动作覆盖
  if (mood.value !== 'success' && mood.value !== 'fail') mood.value = 'user'
}
function onPasswordFocus() {
  if (mood.value !== 'success' && mood.value !== 'fail') mood.value = 'pwd'
}
function onUsernameBlur() {
  if (mood.value === 'user') mood.value = 'idle'
}
function onPasswordBlur() {
  if (mood.value === 'pwd' && !showPassword.value) mood.value = 'idle'
}
function onUsernameInput() {
  errors.username = ''
  errors.form = ''
  // 失败表情在用户重新输入时解除：在哪个框输入就进入哪个框的天气
  if (mood.value === 'fail') mood.value = 'user'
}
function onPasswordInput() {
  errors.password = ''
  errors.form = ''
  if (mood.value === 'fail') mood.value = 'pwd'
}
function togglePassword() {
  showPassword.value = !showPassword.value
  if (showPassword.value) {
    mood.value = 'pwd'
  } else if (document.activeElement !== passwordInputRef.value) {
    mood.value = 'idle'
  }
  passwordInputRef.value?.focus()
}

/* ---------- 记住我：本地持久化用户名 ---------- */
function loadRemembered() {
  const saved = localStorage.getItem(REMEMBER_KEY)
  if (saved) {
    username.value = saved
    remember.value = true
  }
}
function persistRemembered() {
  if (remember.value) {
    localStorage.setItem(REMEMBER_KEY, username.value)
  } else {
    localStorage.removeItem(REMEMBER_KEY)
  }
}

/**
 * 把 axios / 后端的各种失败形态翻译成用户能照做的人话；
 * 绝不把 "Request failed with status code 400" 这类技术黑话甩到界面上。
 */
function normalizeLoginError(e: unknown): string {
  const err = e as {
    response?: { status?: number; data?: { message?: string } }
    code?: string
    message?: string
  }
  const status = err?.response?.status
  const backendMessage = err?.response?.data?.message
  if (status === 401 || status === 403) return '用户名或密码错误，请重新输入'
  if (status === 429) return '尝试过于频繁，请稍后再试'
  if (typeof status === 'number' && status >= 500) return '服务暂时不可用，请稍后再试'
  // 后端业务异常（400 + ApiResponse.fail）自带人话消息，原样使用
  if (backendMessage) return backendMessage
  // 响应拦截器对 success:false reject 的 Error 装的也是后端人话
  const isAxiosNoise =
    !!err?.message && /^Request failed|Network Error|timeout of /i.test(err.message)
  if (err?.message && !isAxiosNoise) return err.message
  if (err?.code === 'ERR_NETWORK' || !err?.response) {
    return '网络连接失败，请检查网络后重试'
  }
  return '登录失败，请稍后重试'
}

/** 处理登录操作：字段级校验 -> 调用store登录 -> 跳转仪表盘 */
async function handleLogin() {
  if (loading.value || submitted.value) return
  // 提交本身就是明确交互：解除休眠，让阵雨/光束正常演出
  wake()

  const name = username.value.trim()
  errors.username = name ? '' : '请输入用户名'
  errors.password = password.value ? '' : '请输入密码'
  errors.form = ''

  if (errors.username || errors.password) {
    mood.value = 'fail'
    if (errors.username) {
      liveStatus.value = errors.username
      usernameInputRef.value?.focus()
    } else {
      liveStatus.value = errors.password
      passwordInputRef.value?.focus()
    }
    return
  }

  loading.value = true
  try {
    await adminStore.login(name, password.value)
    persistRemembered()
    mood.value = 'success'
    submitted.value = true
    liveStatus.value = '登录成功，正在进入系统…'
    ElMessage.success('登录成功')
    // 1400ms：光束 1.12s 才到峰值，之后还要驻留约 0.3s；太早切走会变成「闪一下」
    redirectTimer = window.setTimeout(() => {
      router.push('/admin/dashboard')
    }, 1400)
  } catch (e) {
    mood.value = 'fail'
    const msg = normalizeLoginError(e)
    errors.form = msg
    liveStatus.value = msg
    passwordInputRef.value?.focus()
  } finally {
    // 成功后保持禁用态直到跳转，防止重复提交
    if (!submitted.value) loading.value = false
  }
}

function notifyUnavailable(text: string) {
  ElMessage.info(text)
}

onMounted(() => {
  loadRemembered()
  window.addEventListener('mousemove', wake)
  window.addEventListener('keydown', wake)
  window.addEventListener('pointerdown', wake)
  window.addEventListener('focusin', wake)
  wake()
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', wake)
  window.removeEventListener('keydown', wake)
  window.removeEventListener('pointerdown', wake)
  window.removeEventListener('focusin', wake)
  window.clearTimeout(idleTimer)
  window.clearTimeout(redirectTimer)
})
</script>

<template>
  <div class="login-page">
    <div class="login-shell">
      <!-- 左侧：丰收晨光场景 + 天气剧场（整体对辅助技术隐藏，纯氛围） -->
      <section
        class="stage"
        :class="[
          `is-${mood}`,
          { 'is-dormant': dormant },
        ]"
        aria-hidden="true"
      >
        <img class="stage-bg" :src="harvestBg" alt="" draggable="false" />

        <div class="wx">
          <!-- 晨光增强层：聚焦用户名/成功时变亮 -->
          <div class="wx-rays"></div>
          <!-- 成功时从晨光光源斜切而下的破云光束 -->
          <div class="wx-beam"></div>
          <!-- 光源爆闪：比光束早 60ms，给光束一个「出处」 -->
          <div class="wx-flare"></div>
          <!-- 输密码/失败时飘来遮日的云 -->
          <div class="wx-cloud"><i></i></div>
          <!-- 雨层：克制细雨 / 失败阵雨 -->
          <div class="wx-rain"></div>
          <!-- 阴天冷调遮罩 -->
          <div class="wx-shade"></div>
        </div>

        <div class="stage-caption">
          <strong>知天而作</strong>
          <span>把气象预警和农技帮扶，送到每一块田头</span>
        </div>
      </section>

      <!-- 右侧：登录表单卡片 -->
      <section class="form-side">
        <div class="login-card">
          <header class="card-head">
            <div class="brand">
              <span class="brand-mark" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="8.5" cy="7.5" r="2.6" />
                  <path d="M8.5 2.8v1.6M4 7.5h1.6M5.2 4.2l1.1 1.1" />
                  <path d="M2.6 17.6c2.2-1.8 4.2-1.8 6.4 0 2.2-1.8 4.2-1.8 6.4 0 1.8-1.5 3.8-1.6 6-.2" />
                </svg>
              </span>
              <span class="brand-text">
                <span class="brand-name">乡村农业气象帮扶</span>
                <span class="brand-sub">管理后台</span>
              </span>
            </div>
            <h1>欢迎回来</h1>
            <p>请输入你的账号信息</p>
          </header>

          <form class="login-form" novalidate @submit.prevent="handleLogin">
            <div class="field">
              <label class="field-label" for="login-username">用户名</label>
              <span class="control">
                <svg class="control-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <circle cx="12" cy="8" r="4" />
                  <path d="M4.5 20c.8-3.6 3.8-5.5 7.5-5.5s6.7 1.9 7.5 5.5" stroke-linecap="round" />
                </svg>
                <input
                  id="login-username"
                  ref="usernameInputRef"
                  v-model="username"
                  type="text"
                  placeholder="请输入用户名"
                  autocomplete="username"
                  :aria-invalid="errors.username ? 'true' : 'false'"
                  aria-describedby="username-error"
                  @focus="onUsernameFocus"
                  @blur="onUsernameBlur"
                  @input="onUsernameInput"
                />
              </span>
              <p class="field-error" id="username-error" aria-live="polite">{{ errors.username }}</p>
            </div>

            <div class="field">
              <label class="field-label" for="login-password">密码</label>
              <span class="control">
                <svg class="control-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <rect x="5" y="10.5" width="14" height="9.5" rx="2.5" />
                  <path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" stroke-linecap="round" />
                </svg>
                <input
                  id="login-password"
                  ref="passwordInputRef"
                  v-model="password"
                  :type="showPassword ? 'text' : 'password'"
                  placeholder="请输入密码"
                  autocomplete="current-password"
                  :aria-invalid="errors.password ? 'true' : 'false'"
                  aria-describedby="password-error"
                  @focus="onPasswordFocus"
                  @blur="onPasswordBlur"
                  @input="onPasswordInput"
                />
                <button
                  type="button"
                  class="toggle-eye"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  @click="togglePassword"
                >
                  <svg v-if="showPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M4 4l16 16" stroke-linecap="round" />
                    <path d="M9.9 6.2C10.6 6 11.3 5.9 12 5.9c6 0 9.5 6.1 9.5 6.1-.7 1.2-1.6 2.4-2.7 3.4M6.2 8C4.2 9.5 2.5 12 2.5 12S6 18.5 12 18.5c1.4 0 2.6-.3 3.7-.8" stroke-linecap="round" />
                  </svg>
                </button>
              </span>
              <p class="field-error" id="password-error" aria-live="polite">{{ errors.password }}</p>
            </div>

            <div class="form-row">
              <label class="remember">
                <input v-model="remember" type="checkbox" />
                <span>记住我</span>
              </label>
              <button
                type="button"
                class="text-link"
                @click="notifyUnavailable('请联系管理员重置密码')"
              >
                忘记密码？
              </button>
            </div>

            <p v-if="errors.form" class="form-alert" role="presentation" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="9" />
                <path d="M12 7.5v5" />
                <path d="M12 16.2v.3" />
              </svg>
              <span>{{ errors.form }}</span>
            </p>
            <p class="sr-only" aria-live="polite">{{ liveStatus }}</p>

            <button class="submit-btn" type="submit" :disabled="loading || submitted">
              {{ submitted ? '登录中…' : loading ? '登录中…' : '登录' }}
            </button>
          </form>

          <p class="signup">
            没有账号？
            <button
              type="button"
              class="text-link signup-link"
              @click="notifyUnavailable('账号由管理员统一开通，请联系管理员')"
            >
              注册
            </button>
          </p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ============================================================
   配色：丰收暖橙 + 稻田绿 + 暖纸底
   ============================================================ */
.login-page {
  /* UI 交互主色 = 丰收橙，按钮/链接同色，与画面晨光同温 */
  --brand: #ff8a34;
  --brand-hover: #f97316;
  --brand-ink: #4d2600;
  --brand-deep: #c2410c;
  --brand-press: #9a3412;
  --brand-soft: #fff4ea;
  --page-bg: #f7f3ea;
  --card-border: #ece4d6;
  /* 文字阶：微暖灰 */
  --text-1: #2a241c;
  --text-2: #756d61;
  --text-3: #585043;
  --icon: #7a7266;
  --placeholder: #8b8377;
  --field-border: #e4dccd;
  /* 错误态：砖红 */
  --danger: #d92d20;
  --danger-ink: #b42318;
  --danger-ring: rgba(217, 45, 32, 0.16);
  --danger-soft: #fef3f2;

  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
  background:
    radial-gradient(1000px 560px at 6% -12%, rgba(255, 160, 70, 0.14), transparent 60%),
    radial-gradient(900px 560px at 106% 112%, rgba(134, 181, 116, 0.16), transparent 60%),
    var(--page-bg);
  caret-color: var(--brand-deep);
}

.login-page ::selection {
  background: rgba(255, 138, 52, 0.28);
  color: var(--brand-ink);
}

/* 仅供屏幕阅读器的播报文本 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.login-shell {
  width: min(1180px, 100%);
  display: grid;
  grid-template-columns: 1.2fr 0.8fr;
  align-items: center;
  gap: 46px;
}

/* ============================================================
   左侧：丰收晨光舞台 + 天气剧场
   ============================================================ */
.stage {
  position: relative;
  min-height: 560px;
  border-radius: 26px;
  overflow: hidden;
  background: #e9e2d2;
  box-shadow: 0 24px 60px rgba(94, 66, 24, 0.16);
}

.stage-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: 30% center;
  transition: filter 0.75s ease;
  user-select: none;
}

/* 天气状态对画面色调的影响 */
.is-pwd .stage-bg {
  filter: saturate(0.8) brightness(0.96);
}
.is-fail .stage-bg {
  filter: saturate(0.52) brightness(0.9);
}
/* 成功态刻意不提亮画面：把「变亮」全交给光束，两者都提亮会互相稀释 */
.is-success .stage-bg {
  filter: saturate(1.12) brightness(1);
}

.wx {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
}

/* 晨光：idle 缓慢呼吸，聚焦用户名/成功时变亮，输密码/失败时收起 */
.wx-rays {
  position: absolute;
  inset: 0;
  opacity: 0.14;
  background:
    radial-gradient(420px 300px at 14% 4%, rgba(255, 205, 110, 0.42), transparent 72%),
    linear-gradient(115deg, rgba(255, 222, 160, 0.32), rgba(255, 222, 160, 0.08) 24%, transparent 46%);
  animation: rays-breathe 7s ease-in-out infinite;
  transition: opacity 0.8s ease;
}
@keyframes rays-breathe {
  0%,
  100% {
    opacity: 0.1;
  }
  50% {
    opacity: 0.2;
  }
}
.is-user .wx-rays {
  animation: none;
  opacity: 0.42;
}
.is-success .wx-rays {
  animation: none;
  /* 退居背景：只留一层薄底光，亮度主角是光束 */
  opacity: 0.34;
  transition-delay: 0.25s;
}
.is-pwd .wx-rays,
.is-fail .wx-rays {
  animation: none;
  opacity: 0;
}

/* 破云光束：仅成功时从晨光光源斜切而下（雨停 -> 云散 -> 光出） */
.wx-beam {
  position: absolute;
  top: -22%;
  left: -16%;
  width: 92%;
  height: 132%;
  opacity: 0;
  /* 以光源点为原点张开，光束像是从那里长出来的 */
  transform: rotate(19deg) scale(0.96);
  transform-origin: 18% 12%;
  /* 必须用 overlay：这张插画整体偏亮，screen 叠光在近白区域等于没效果 */
  mix-blend-mode: overlay;
  /* 一亮一窄两条光柱，不加暗隙：暗隙会在人物和田地上留下像污渍的灰条 */
  background: repeating-linear-gradient(
    97deg,
    rgba(255, 250, 236, 1) 0 15%,
    transparent 15% 30%,
    rgba(255, 248, 226, 0.45) 30% 38%,
    transparent 38% 62%
  );
  filter: blur(14px);
  /* 沿光轴单向擦除：让光束「扫出来」而不是整块淡入。
     ⚠️ 只写 mask-image，不要补 -webkit- 前缀：esbuild 会把两者当同一属性、
     只保留后写的 -webkit- 版，和 backdrop-filter 是同一个坑。
     mask-position 百分比换算 X0 = (容器 100% - 图 260%) × p：
     p=132% → X0=-211%，元素左缘落在图内 211%（> 透明起点 202.8%）→ 全遮；
     p=0    → X0=0，元素全部落在图内不透明区（0~117%）→ 全显。 */
  mask-image: linear-gradient(97deg, #000 0 45%, transparent 78%);
  mask-size: 260% 100%;
  mask-repeat: no-repeat;
  mask-position: 132% 0;
}
/* 形态与亮度拆两段：先 0.38s 擦出，再 0.82s 升到峰值并驻留，别再用整块淡入 */
.is-success .wx-beam {
  animation:
    beam-sweep 0.38s cubic-bezier(0.22, 0.8, 0.3, 1) 0.3s both,
    beam-bright 0.82s ease-out 0.3s forwards;
}
@keyframes beam-sweep {
  from {
    mask-position: 132% 0;
  }
  to {
    mask-position: 0% 0;
  }
}
@keyframes beam-bright {
  0% {
    opacity: 0;
    transform: rotate(19deg) scale(0.96);
  }
  46% {
    opacity: 0.42;
  }
  100% {
    opacity: 0.68;
    transform: rotate(19deg) scale(1);
  }
}

/* 光源爆闪：比光束早 60ms，先「光源亮了」再「光射出来」，消除凭空出现的突兀感 */
.wx-flare {
  position: absolute;
  left: 4%;
  top: -4%;
  width: 36%;
  height: 38%;
  opacity: 0;
  background: radial-gradient(
    circle at 50% 50%,
    rgba(255, 246, 220, 0.95),
    rgba(255, 234, 184, 0.4) 36%,
    transparent 70%
  );
  /* 小面积高光，screen 在这里才是想要的过曝感 */
  mix-blend-mode: screen;
}
.is-success .wx-flare {
  animation: flare-pop 0.62s ease-out 0.24s forwards;
}
@keyframes flare-pop {
  0% {
    opacity: 0;
    transform: scale(0.7);
  }
  18% {
    opacity: 0.8;
  }
  100% {
    opacity: 0;
    transform: scale(1.14);
  }
}

/* 遮日云：输密码飘来、失败变灰压低，晴天移出画面 */
.wx-cloud {
  position: absolute;
  top: -8px;
  left: -8px;
  width: 158px;
  height: 56px;
  color: #e9edf2;
  opacity: 0;
  transform: translate(-72px, -44px) scale(0.9);
  transition:
    transform 0.9s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.8s ease,
    color 0.8s ease;
  filter: drop-shadow(0 6px 10px rgba(60, 50, 30, 0.12));
}
.wx-cloud i,
.wx-cloud::before,
.wx-cloud::after {
  content: '';
  position: absolute;
  background: currentColor;
  border-radius: 50%;
}
.wx-cloud i {
  left: 36px;
  top: 16px;
  width: 84px;
  height: 34px;
  border-radius: 99px;
}
.wx-cloud::before {
  left: 48px;
  top: 0;
  width: 46px;
  height: 46px;
}
.wx-cloud::after {
  left: 82px;
  top: 9px;
  width: 33px;
  height: 33px;
}
.is-pwd .wx-cloud {
  opacity: 0.94;
  transform: translate(12px, 10px) scale(1);
}
.is-fail .wx-cloud {
  opacity: 1;
  color: #aab5bf;
  transform: translate(16px, 14px) scale(1.12);
}
.is-user .wx-cloud {
  opacity: 0;
  transform: translate(-52px, -60px) scale(0.8);
}
/* 成功态错峰：云先散（+0.08s），光源闪（+0.24s），光束后到（+0.30s） */
.is-success .wx-cloud {
  opacity: 0;
  transform: translate(-52px, -60px) scale(0.8);
  transition-delay: 0.08s;
}

/* 雨层：斜向雨丝，背景位移模拟下落；pwd 克制细雨，fail 阵雨 */
.wx-rain {
  position: absolute;
  inset: 0;
  opacity: 0;
  background-image: repeating-linear-gradient(
    106deg,
    rgba(206, 225, 238, 0.9) 0 1.5px,
    transparent 1.5px 18px
  );
  background-size: 52px 78px;
  transition: opacity 0.6s ease;
}
.is-pwd .wx-rain {
  opacity: 0.16;
  animation: rain-fall 0.95s linear infinite;
}
.is-fail .wx-rain {
  opacity: 0.5;
  animation: rain-fall 0.55s linear infinite;
}
/* 成功时雨收得比来时快，把画面让给光束 */
.is-success .wx-rain {
  transition-duration: 0.4s;
}
@keyframes rain-fall {
  to {
    background-position: -16px 78px;
  }
}

/* 阴天冷调遮罩：雨天压暗、偏冷 */
.wx-shade {
  position: absolute;
  inset: 0;
  opacity: 0;
  background: linear-gradient(
    180deg,
    rgba(48, 66, 84, 0.18),
    rgba(48, 66, 84, 0.05) 45%,
    rgba(48, 66, 84, 0.14)
  );
  transition: opacity 0.7s ease;
}
.is-pwd .wx-shade {
  opacity: 0.45;
}
.is-fail .wx-shade {
  opacity: 1;
}

/* 画面底部渐变，托住白色标语 */
.stage::after {
  content: '';
  position: absolute;
  z-index: 1;
  left: 0;
  right: 0;
  bottom: 0;
  height: 42%;
  background: linear-gradient(180deg, transparent, rgba(40, 26, 8, 0.4));
  pointer-events: none;
  transition: opacity 0.7s ease;
}
.is-fail .stage::after {
  opacity: 0.7;
}

.stage-caption {
  position: absolute;
  z-index: 2;
  left: 28px;
  right: 28px;
  bottom: 24px;
  color: #fff;
  text-shadow: 0 1px 12px rgba(60, 38, 10, 0.5);
}
.stage-caption strong {
  display: block;
  margin-bottom: 5px;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 4px;
}
.stage-caption span {
  font-size: 12.5px;
  letter-spacing: 0.6px;
  opacity: 0.95;
}

/* idle 休眠：循环动效全部静止（云/光束/滤镜为状态切换，不受影响） */
.stage.is-dormant .wx-rays,
.stage.is-dormant .wx-rain {
  animation-play-state: paused;
}

/* ============================================================
   右侧表单
   ============================================================ */
.form-side {
  display: flex;
  justify-content: center;
}
.login-card {
  width: min(400px, 100%);
  padding: 40px 40px 32px;
  background: #fffdf8;
  border: 1px solid var(--card-border);
  border-radius: 22px;
  box-shadow: 0 10px 36px rgba(94, 66, 24, 0.09);
  animation: card-in 0.55s cubic-bezier(0.22, 1, 0.36, 1) both;
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.card-head {
  margin-bottom: 24px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}
.brand-mark {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  color: var(--brand-deep);
  background: var(--brand-soft);
}
.brand-mark svg {
  width: 22px;
  height: 22px;
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: 0.2px;
}
.brand-sub {
  font-size: 12px;
  color: var(--text-2);
}
.card-head h1 {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: 0.5px;
}
.card-head p {
  margin: 0;
  font-size: 14px;
  color: var(--text-2);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-3);
}
.field-error {
  margin: 0 2px;
  font-size: 12.5px;
  line-height: 1.45;
  color: var(--danger-ink);
}
.field-error:empty {
  display: none;
}
.control input[aria-invalid='true'] {
  border-color: var(--danger);
  background: #fff;
  box-shadow: 0 0 0 3px var(--danger-ring);
}
.control:has(input[aria-invalid='true']) .control-icon {
  color: var(--danger);
}
.form-alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid rgba(217, 45, 32, 0.25);
  border-radius: 10px;
  background: var(--danger-soft);
  color: var(--danger-ink);
  font-size: 13px;
  line-height: 1.5;
}
.form-alert svg {
  flex: none;
  width: 17px;
  height: 17px;
  margin-top: 1px;
}
.control {
  position: relative;
  display: flex;
  align-items: center;
}
.control-icon {
  position: absolute;
  left: 12px;
  width: 18px;
  height: 18px;
  color: var(--icon);
  pointer-events: none;
  transition: color 0.2s ease;
}
.control input {
  width: 100%;
  height: 48px;
  padding: 0 44px;
  font-size: 14px;
  color: var(--text-1);
  background: #fbf8f2;
  border: 1.5px solid var(--field-border);
  border-radius: 12px;
  outline: none;
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease,
    box-shadow 0.2s ease;
}
.control input::placeholder {
  color: var(--placeholder);
}
.control input:focus {
  border-color: var(--brand-deep);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(194, 65, 12, 0.18);
}
.control:focus-within .control-icon {
  color: var(--brand-deep);
}
.toggle-eye {
  position: absolute;
  right: 8px;
  width: 36px;
  height: 36px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--icon);
  cursor: pointer;
  transition:
    color 0.2s ease,
    background-color 0.2s ease;
}
.toggle-eye:hover {
  color: var(--brand-deep);
  background: var(--brand-soft);
}
.toggle-eye:focus-visible {
  outline: 2px solid var(--brand-press);
  outline-offset: 1px;
}
.toggle-eye svg {
  width: 18px;
  height: 18px;
}

.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.remember {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-3);
  cursor: pointer;
  user-select: none;
}
.remember input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: var(--brand-deep);
  cursor: pointer;
}
.remember input:focus-visible {
  outline: 2px solid var(--brand-press);
  outline-offset: 2px;
}
.text-link {
  padding: 0;
  border: 0;
  background: none;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.4;
  color: var(--brand-deep);
  text-decoration: none;
  cursor: pointer;
  transition: color 0.2s ease;
}
.text-link:hover {
  color: var(--brand-press);
}
.text-link:focus-visible {
  outline: 2px solid var(--brand-press);
  outline-offset: 2px;
  border-radius: 4px;
}

.submit-btn {
  height: 48px;
  margin-top: 4px;
  border: 0;
  border-radius: 12px;
  background: var(--brand);
  color: var(--brand-ink);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 2px;
  text-indent: 2px;
  cursor: pointer;
  box-shadow: 0 8px 18px rgba(194, 65, 12, 0.22);
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    background-color 0.2s ease;
}
.submit-btn:hover {
  background: var(--brand-hover);
  transform: translateY(-1px);
  box-shadow: 0 12px 24px rgba(194, 65, 12, 0.28);
}
.submit-btn:active {
  transform: translateY(0) scale(0.99);
}
.submit-btn:focus-visible {
  outline: 2px solid var(--brand-press);
  outline-offset: 2px;
}
.submit-btn:disabled {
  opacity: 0.7;
  cursor: default;
  transform: none;
}

.signup {
  margin: 24px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--text-2);
}
.signup a,
.signup .signup-link {
  margin-left: 4px;
  color: var(--brand-deep);
  font-weight: 600;
  text-decoration: none;
  transition: color 0.2s ease;
}
.signup a:hover,
.signup .signup-link:hover {
  color: var(--brand-press);
}
.signup a:focus-visible,
.signup .signup-link:focus-visible {
  outline: 2px solid var(--brand-press);
  outline-offset: 2px;
  border-radius: 4px;
}

/* ============================================================
   响应式
   1100px：收紧栏距与舞台高度
   960px：单列，舞台收成顶部横幅，小屏也保留场景氛围
   ============================================================ */
@media (max-width: 1100px) {
  .login-shell {
    gap: 32px;
  }
  .stage {
    min-height: 480px;
  }
}

@media (max-width: 960px) {
  .login-shell {
    grid-template-columns: 1fr;
    gap: 22px;
    width: min(420px, 100%);
  }
  .stage {
    order: -1;
    min-height: 0;
    height: 200px;
    border-radius: 18px;
  }
  .stage-bg {
    object-position: 50% 68%;
  }
  /* 横幅裁掉了太阳区域，遮日云不再出现；光束保留，按横幅比例收窄 */
  .wx-cloud {
    display: none;
  }
  /* 横幅只有 200px 高，光束按同样比例压扁，模糊半径同步收小 */
  .wx-beam {
    top: -46%;
    left: -20%;
    width: 96%;
    height: 192%;
    filter: blur(10px);
  }
  /* 横幅只有 200px 高，光源爆闪按横幅比例压扁，别糊满整条 */
  .wx-flare {
    left: 2%;
    top: -20%;
    width: 30%;
    height: 58%;
  }
  .stage-caption {
    left: 18px;
    bottom: 14px;
  }
  .stage-caption strong {
    font-size: 17px;
    letter-spacing: 3px;
  }
  .stage-caption span {
    display: none;
  }
  .login-card {
    padding: 32px 28px 26px;
  }
}

@media (max-width: 480px) {
  .login-page {
    padding: 16px;
  }
  .stage {
    height: 160px;
    border-radius: 14px;
  }
  .login-card {
    padding: 30px 20px 26px;
    border-radius: 16px;
  }
  .card-head h1 {
    font-size: 23px;
  }
}

/* 尊重系统“减少动态效果”设置：循环天气动效与入场动画全停，状态切换保留瞬时反馈 */
@media (prefers-reduced-motion: reduce) {
  .wx-rays,
  .wx-rain,
  .login-card {
    animation: none;
  }
  .stage-bg,
  .wx-cloud,
  .wx-rain,
  .wx-rays,
  .wx-shade,
  .stage::after {
    transition: none;
  }
  /* 淡入不属于 motion：光束保留柔和出现，去掉扫出与位移；光源爆闪一并关掉 */
  .wx-beam {
    mask-image: none;
    transform: rotate(19deg) scale(1);
  }
  .is-success .wx-beam {
    animation: none;
    opacity: 0.62;
    transition: opacity 0.35s ease 0.15s;
  }
  .wx-flare {
    display: none;
  }
}
</style>

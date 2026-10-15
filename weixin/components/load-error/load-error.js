/**
 * 通用「加载失败 · 重试」组件。
 *
 * 为什么要抽成组件：首页最早只有一处手写错误条，其它页面（农事/社区/我的作物…）
 * 的 catch 全是静默的 —— 真机上一断网就是一片空白，用户以为程序坏了。
 * 统一走这个组件后：错误态的文案、配色、按钮位置在所有页面都一致，
 * 页面只需要维护一个 error 标记 + 一个 onRetry 处理函数。
 *
 * 用法：
 *   <load-error wx:if="{{loadError}}" msg="{{loadErrorMsg}}" bind:retry="onRetryLoad" />
 */
Component({
  properties: {
    /** 是否展示（由页面控制，也可以直接用 wx:if 挂在标签上） */
    show: { type: Boolean, value: true },
    /** 主文案 */
    title: { type: String, value: '内容没能加载出来' },
    /** 具体原因（errMsg），可为空 */
    msg: { type: String, value: '' },
    /** 按钮文案 */
    btnText: { type: String, value: '重新加载' }
  },

  methods: {
    onRetry() {
      // 只冒泡事件，不自己发请求 —— 每个页面的重试逻辑不同（有的要重拉 tab，有的要重拉整页）
      this.triggerEvent('retry')
    }
  }
})

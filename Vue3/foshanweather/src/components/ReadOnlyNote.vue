<script setup lang="ts">
/** 只读提示条：写入口被角色收掉时说清「现在能做什么、要找谁」。六个页面共用，样式统一放这里。 */
import { InfoFilled } from '@element-plus/icons-vue'

defineProps<{ text: string }>()
</script>

<template>
  <div class="ro-note">
    <el-icon class="ro-note-icon"><InfoFilled /></el-icon>
    <span>{{ text }}</span>
  </div>
</template>

<style scoped>
/* 琥珀色而非红色：这里是权限说明不是错误，红色已被「灾害 / 风险等级」占用。 */
.ro-note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  background: #fdf3e3;
  border: 1px solid #f5e2c2;
  font-size: 13px;
  color: #8a5a1a;
  line-height: 1.6;
}
.ro-note-icon {
  margin-top: 2px;
  flex-shrink: 0;
}
</style>

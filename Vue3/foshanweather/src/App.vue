﻿<script setup lang="ts">
/** 根路由视图组件，作为所有页面路由的容器 */
import { RouterView } from 'vue-router'
</script>

<template>
  <!-- 路由视图出口，渲染当前路由匹配的组件 -->
  <RouterView />
</template>

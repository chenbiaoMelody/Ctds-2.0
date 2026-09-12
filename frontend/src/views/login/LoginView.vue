<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { signInDemo } from '../../stores/demoAuth'

/**
 * WBS-2.4.12 骨架期演示登录页（hifi §1）：非真实认证——任意非空账号口令进入，
 * 真实认证/令牌签发待 3.9.1 规格澄清，本页不连接后端（2.4.9 划归留痕）。
 * 独立布局（不套 MainLayout）；已登录访问 /login 由路由守卫重定向工作台。
 */
const router = useRouter()
const formRef = ref<FormInstance>()
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入口令', trigger: 'blur' }],
}

async function onLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  signInDemo()
  router.push('/dashboard')
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1 class="login-title">C-TDS 数据空间</h1>
      <p class="login-subtitle">城市可信数据空间平台</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" name="username" />
        </el-form-item>
        <el-form-item label="口令" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入口令"
            show-password
            name="password"
          />
        </el-form-item>
        <el-button type="primary" class="login-btn" @click="onLogin">登 录</el-button>
      </el-form>
      <el-alert
        class="demo-note"
        type="info"
        :closable="false"
        show-icon
        title="骨架期演示登录：任意非空账号口令即可进入；真实认证/令牌签发待 3.9.1 规格澄清，本页不连接后端"
      />
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #111827;
}

.login-card {
  width: 380px;
}

.login-title {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: #111827;
  letter-spacing: 1px;
}

.login-subtitle {
  margin: 6px 0 18px;
  font-size: 13px;
  color: #6b7280;
}

.login-btn {
  width: 100%;
  margin-bottom: 16px;
}
</style>

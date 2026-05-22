<template>
  <div class="auth-container">
    <div class="auth-card">
      <div class="auth-header">
        <span class="duck">🐤</span>
        <h2>Sakura Agent</h2>
        <p>登录后绘梨衣才能记得你</p>
      </div>

      <div class="tabs">
        <button :class="{active: isLogin}" @click="isLogin=true">登录</button>
        <button :class="{active: !isLogin}" @click="isLogin=false">注册</button>
      </div>

      <form @submit.prevent="submit">
        <input v-model="username" placeholder="用户名" required minlength="2" />
        <input v-model="password" type="password" placeholder="密码" required minlength="4" />
        <p class="error" v-if="error">{{ error }}</p>
        <p class="success" v-if="success">{{ success }}</p>
        <button type="submit" :disabled="loading">
          {{ loading ? '请稍候...' : (isLogin ? '登录' : '注册') }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, register, isLoggedIn } from '../api'

const router = useRouter()
const isLogin = ref(true)
const username = ref('')
const password = ref('')
const error = ref('')
const success = ref('')
const loading = ref(false)

if (isLoggedIn()) router.push('/')

const submit = async () => {
  error.value = ''
  success.value = ''
  loading.value = true
  try {
    const fn = isLogin.value ? login : register
    const res = await fn(username.value, password.value)
    if (res.code === 200) {
      success.value = res.msg
      setTimeout(() => router.push('/'), 800)
    } else {
      error.value = res.msg
    }
  } catch (e) {
    error.value = '网络错误，请检查后端是否启动'
  }
  loading.value = false
}
</script>

<style scoped>
.auth-container { display:flex; align-items:center; justify-content:center; min-height:100vh; background:#1a1a2e; }
.auth-card { background:#16213e; padding:40px; border-radius:16px; width:380px; box-shadow:0 8px 32px rgba(0,0,0,0.3); }
.auth-header { text-align:center; margin-bottom:24px; }
.auth-header .duck { font-size:48px; }
.auth-header h2 { color:#e94560; margin:8px 0; }
.auth-header p { color:#888; font-size:14px; }
.tabs { display:flex; margin-bottom:20px; gap:0; }
.tabs button { flex:1; padding:10px; border:none; background:transparent; color:#888; cursor:pointer; font-size:14px; border-bottom:2px solid transparent; }
.tabs button.active { color:#e94560; border-bottom-color:#e94560; }
form { display:flex; flex-direction:column; gap:12px; }
input { padding:12px; border-radius:8px; border:1px solid #0f3460; background:#1a1a2e; color:#eee; font-size:14px; outline:none; }
input:focus { border-color:#e94560; }
button[type=submit] { padding:12px; border:none; border-radius:8px; background:#e94560; color:#fff; font-size:14px; cursor:pointer; }
button[type=submit]:disabled { opacity:0.6; }
.error { color:#ff6b6b; font-size:13px; text-align:center; }
.success { color:#51cf66; font-size:13px; text-align:center; }
</style>

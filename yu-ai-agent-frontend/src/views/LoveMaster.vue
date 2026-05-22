<template>
  <div class="app">
    <!-- 左侧对话列表 -->
    <aside class="sidebar">
      <button class="new-btn" @click="newChat">+ 新对话</button>
      <nav class="conv-list">
        <div v-for="c in conversations" :key="c.chatId"
             class="conv-item" :class="{ active: c.chatId === currentChatId }"
             @click="switchChat(c)" @contextmenu.prevent="ctxMenu($event, c)">
          <div class="conv-title">{{ c.title || '新对话' }}</div>
        </div>
        <div v-if="conversations.length===0" class="empty">暂无对话</div>
      </nav>

      <!-- 右键菜单 -->
      <div v-if="menu.visible" class="ctx-menu" :style="{top:menu.y+'px', left:menu.x+'px'}">
        <div class="ctx-item" @click="delConv">🗑 删除对话</div>
      </div>
    </aside>

    <!-- 右侧聊天 -->
    <main class="chat-main">
      <header class="topbar">
        <span class="duck">🐤</span>
        <span>上杉绘梨衣</span>
        <span class="home-link" @click="goBack">← 返回</span>
      </header>

      <div class="messages" ref="msgBox">
        <div v-for="(m,i) in messages" :key="i" :class="['msg', m.isUser ? 'user' : 'ai']">
          {{ m.content }}
        </div>
      </div>

      <div class="input-row">
        <input v-model="input" @keydown.enter="send" placeholder="在小本本上写字..."
               :disabled="sending" />
        <button @click="send" :disabled="sending || !input.trim()">发送</button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { chatWithLoveApp, getConversations, getMessages, deleteConversation } from '../api'

const router = useRouter()
const messages = ref([])
const input = ref('')
const sending = ref(false)
const currentChatId = ref('')
const conversations = ref([])
const msgBox = ref(null)
let eventSource = null

const genId = () => 'love_' + Math.random().toString(36).substring(2,10)

const add = (text, isUser) => {
  messages.value.push({ content: text, isUser })
  nextTick(() => { if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight })
}

const loadConvs = async () => {
  try {
    const res = await getConversations()
    console.log('[Conversations]', res)
    conversations.value = Array.isArray(res) ? res : []
  } catch (e) {
    console.error('[Conversations] 加载失败', e)
  }
}

const menu = ref({ visible: false, x: 0, y: 0, chatId: '' })
const ctxMenu = (e, c) => {
  menu.value = { visible: true, x: e.clientX, y: e.clientY, chatId: c.chatId }
}
const delConv = async () => {
  if (confirm('确定删除这个对话吗？')) {
    await deleteConversation(menu.value.chatId)
    if (currentChatId.value === menu.value.chatId) newChat()
    loadConvs()
  }
  menu.value.visible = false
}
document.addEventListener('click', () => { menu.value.visible = false })

const send = () => {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  add(text, true)
  sending.value = true
  if (eventSource) eventSource.close()
  const idx = messages.value.length
  add('', false)
  eventSource = chatWithLoveApp(text, currentChatId.value)
  eventSource.onmessage = e => {
    if (e.data && idx < messages.value.length)
      messages.value[idx].content += e.data
  }
  eventSource.onerror = () => { sending.value = false; eventSource.close(); setTimeout(loadConvs, 500) }
}

const newChat = () => {
  if (eventSource) eventSource.close()
  currentChatId.value = genId()
  messages.value = []
  add('📝 翻开小本本... 你好，我是上杉绘梨衣！你想和我说什么？', false)
}

const switchChat = async (c) => {
  if (eventSource) eventSource.close()
  currentChatId.value = c.chatId
  try {
    const history = await getMessages(c.chatId, c.conversationId)
    messages.value = history.map(m => ({
      content: m.content,
      isUser: m.role === 'user',
      time: Date.now()
    }))
  } catch (e) {
    messages.value = []
  }
}

const goBack = () => router.push('/')

onMounted(() => {
  loadConvs()
  newChat()
})
</script>

<style>
* { margin:0; padding:0; box-sizing:border-box; }
html, body, #app { height:100%; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }
</style>

<style scoped>
.app { display:flex; height:100vh; background:#1a1a2e; color:#eee; }

.sidebar {
  width:260px; background:#16213e; display:flex; flex-direction:column;
  border-right:1px solid #0f3460; flex-shrink:0;
}
.new-btn {
  margin:12px; padding:10px; border:1px solid #e94560; border-radius:8px;
  background:transparent; color:#e94560; cursor:pointer; font-size:14px;
}
.new-btn:hover { background:#e94560; color:#fff; }
.conv-list { flex:1; overflow-y:auto; padding:0 8px 12px; }
.conv-item { padding:10px 12px; border-radius:6px; cursor:pointer; margin-bottom:2px; font-size:13px; }
.conv-item:hover, .conv-item.active { background:#0f3460; }
.conv-title { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.empty { color:#666; text-align:center; margin-top:20px; font-size:13px; }
.ctx-menu { position:fixed; background:#2a2a4a; border:1px solid #444; border-radius:6px; padding:4px 0; z-index:999; min-width:120px; }
.ctx-item { padding:8px 16px; cursor:pointer; font-size:13px; }
.ctx-item:hover { background:#e94560; }

.chat-main { flex:1; display:flex; flex-direction:column; }
.topbar {
  display:flex; align-items:center; justify-content:space-between;
  padding:12px 20px; background:#16213e; border-bottom:1px solid #0f3460;
  color:#e94560; font-weight:bold;
}
.duck { font-size:22px; }
.home-link { font-size:14px; color:#aaa; cursor:pointer; font-weight:normal; }
.home-link:hover { color:#e94560; }

.messages { flex:1; overflow-y:auto; padding:16px; }
.msg { max-width:75%; padding:10px 14px; border-radius:12px; line-height:1.5; white-space:pre-wrap; word-break:break-word; font-size:14px; margin-bottom:8px; }
.msg.user { margin-left:auto; background:#e94560; color:#fff; }
.msg.ai { background:#0f3460; color:#ccc; }

.input-row { display:flex; gap:8px; padding:12px 16px; background:#16213e; border-top:1px solid #0f3460; }
.input-row input { flex:1; padding:10px 14px; border-radius:8px; border:none; background:#1a1a2e; color:#eee; font-size:14px; outline:none; }
.input-row input:focus { box-shadow:0 0 0 2px #e94560; }
.input-row button { padding:10px 20px; border:none; border-radius:8px; background:#e94560; color:#fff; cursor:pointer; font-size:14px; }
.input-row button:hover { background:#c73e54; }
.input-row button:disabled { opacity:0.5; cursor:default; }
</style>

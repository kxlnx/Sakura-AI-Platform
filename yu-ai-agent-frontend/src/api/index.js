import axios from 'axios'

const API_BASE_URL = ''

const request = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000
})

// ====== 认证相关 ======

/** 从 localStorage 拿 token */
export const getToken = () => localStorage.getItem('token')
export const getUserId = () => localStorage.getItem('userId')
export const getUsername = () => localStorage.getItem('username')

/** 注册 */
export const register = async (username, password) => {
  const { data } = await request.post('/auth/register', { username, password })
  if (data.code === 200) {
    localStorage.setItem('token', data.token)
    localStorage.setItem('userId', data.userId)
    localStorage.setItem('username', data.username)
  }
  return data
}

/** 登录 */
export const login = async (username, password) => {
  const { data } = await request.post('/auth/login', { username, password })
  if (data.code === 200) {
    localStorage.setItem('token', data.token)
    localStorage.setItem('userId', data.userId)
    localStorage.setItem('username', data.username)
  }
  return data
}

/** 登出 */
export const logout = async () => {
  const token = getToken()
  if (token) await request.post('/auth/logout', {}, { headers: { token } })
  localStorage.removeItem('token')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
}

/** 是否已登录 */
export const isLoggedIn = () => !!getToken()

// ====== SSE 连接（带 token 认证） ======

export const connectSSE = (url, params, onMessage, onError) => {
  const token = getToken()
  const queryParams = { ...params }
  if (token) queryParams.token = token  // 通过 query string 传 token（SSE 不支持自定义 header）

  const queryString = Object.keys(queryParams)
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(queryParams[key])}`)
    .join('&')

  const fullUrl = `${API_BASE_URL}${url}?${queryString}`
  const eventSource = new EventSource(fullUrl)

  eventSource.onmessage = event => {
    let data = event.data
    if (data === '[DONE]') {
      if (onMessage) onMessage('[DONE]')
    } else {
      if (onMessage) onMessage(data)
    }
  }

  eventSource.onerror = error => {
    if (onError) onError(error)
    eventSource.close()
  }

  return eventSource
}

// ====== 聊天 API ======

export const chatWithLoveApp = (message, chatId) => {
  const userId = getUserId()
  const params = { message, chatId }
  if (userId) params.userId = userId
  return connectSSE('/ai/love_app/chat/sse', params)
}

export const chatWithManus = (message, userId) => {
  const params = { message }
  if (userId) params.userId = userId
  return connectSSE('/ai/manus/chat', params)  // Agent 对话 ID 由后端固定
}

/** 删除对话 */
export const deleteConversation = async (chatId) => {
  const token = getToken()
  const uid = getUserId()
  let params = '?chatId=' + chatId
  if (uid) params += '&userId=' + uid
  if (token) params += '&token=' + token
  const { data } = await request.get('/ai/conversations/delete' + params)
  return data
}

/** 获取某个对话的历史消息 */
export const getMessages = async (chatId, conversationId) => {
  const token = getToken()
  const uid = getUserId()
  let params = '?chatId=' + chatId
  if (conversationId) params += '&conversationId=' + conversationId
  if (uid) params += '&userId=' + uid
  if (token) params += '&token=' + token
  const { data } = await request.get('/ai/messages' + params)
  return data
}

/** 获取用户的对话列表 */
export const getConversations = async () => {
  const token = getToken()
  const uid = getUserId()
  let params = uid ? '?userId=' + uid : ''
  if (token) params += '&token=' + token
  const { data } = await request.get('/ai/conversations' + params)
  return data
}

export default {
  getToken, getUserId, getUsername,
  register, login, logout, isLoggedIn,
  chatWithLoveApp, chatWithManus,
  getConversations
}

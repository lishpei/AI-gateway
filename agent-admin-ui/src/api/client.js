import axios from 'axios'
import { message } from 'antd'

const TOKEN_KEY = 'a2a_admin_token'

export const getToken = () => localStorage.getItem(TOKEN_KEY)
export const setToken = (t) => localStorage.setItem(TOKEN_KEY, t)
export const clearToken = () => localStorage.removeItem(TOKEN_KEY)

const client = axios.create({ baseURL: '/api/v1', timeout: 15000 })

client.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => {
    const body = resp.data
    // card-preview 返回纯 JSON 字符串（不是统一响应体）
    if (typeof body === 'string') return body
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      if (body.code === 40101) {
        clearToken()
        if (location.pathname !== '/login') location.href = '/login'
      }
      message.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (err) => {
    if (err.response?.status === 401) {
      clearToken()
      if (location.pathname !== '/login') location.href = '/login'
    }
    message.error(err.response?.data?.message || err.message)
    return Promise.reject(err)
  }
)

export default client

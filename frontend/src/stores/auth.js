import { ref } from 'vue'
import { defineStore } from 'pinia'
import { api, postWithCsrf } from '../api/portal'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  function clear() { user.value = null }
  async function restore() {
    try { user.value = (await api.get('/auth/me')).data }
    catch (error) {
      clear()
      if (error.response?.status !== 401) throw error
    }
  }
  async function login(username, password) {
    await postWithCsrf('/auth/login', new URLSearchParams({ username, password }))
    await restore()
  }
  async function logout() {
    await postWithCsrf('/auth/logout')
    clear()
  }
  return { user, clear, restore, login, logout }
})

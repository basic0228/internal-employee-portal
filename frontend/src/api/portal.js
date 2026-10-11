import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })
let unauthorized = () => {}
export function onUnauthorized(handler) { unauthorized = handler }
api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) unauthorized()
  return Promise.reject(error)
})

async function csrfHeaders() {
  // Fetch a fresh masked token, including after login rotates the session/CSRF token.
  const { data } = await api.get('/auth/csrf')
  return { [data.headerName]: data.token }
}
export async function postWithCsrf(path, body) {
  return api.post(path, body, { headers: await csrfHeaders() })
}
export async function putWithCsrf(path, body) {
  return api.put(path, body, { headers: await csrfHeaders() })
}

export function errorMessage(error) {
  if (error.response?.data?.message) return error.response.data.message
  if (error.response?.status === 401) return '로그인이 필요하거나 계정 사용이 제한되었습니다.'
  if (error.response?.status === 403) return '요청 권한 또는 보안 토큰을 확인하세요.'
  if (error.response?.status === 404) return '요청한 정보를 찾을 수 없습니다.'
  return '요청을 처리할 수 없습니다. 잠시 후 다시 시도하세요.'
}

import { api } from '../api/portal'
export async function streamExperiment(body, accept, signal, campaignId, caseId) {
  const { data: token } = await api.get('/auth/csrf')
  const headers = { 'Content-Type': 'application/json', [token.headerName]: token.token }
  if (campaignId) { headers['X-Lab-Campaign'] = campaignId; headers['X-Lab-Case'] = caseId }
  const response = await fetch('/api/admin/reliability-lab/experiments', {
    method: 'POST', credentials: 'same-origin', headers, body: JSON.stringify(body), signal
  })
  if (!response.ok) {
    if (response.status === 401) await api.get('/auth/me')
    const message = await response.json().catch(() => ({}))
    throw new Error(message.message || '실험을 시작할 수 없습니다. 권한·CSRF·서버 설정을 확인하세요.')
  }
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let pending = '', result
  function line(text) {
    if (!text.trim()) return
    const event = JSON.parse(text); accept(event)
    if (event.type === 'result') result = event.data
  }
  while (true) {
    const { done, value } = await reader.read()
    pending += decoder.decode(value || new Uint8Array(), { stream: !done })
    const lines = pending.split('\n'); pending = lines.pop(); lines.forEach(line)
    if (done) { line(pending); break }
  }
  if (!result) throw new Error('최종 이벤트가 없습니다. 서버 저장에서 실행 상태를 확인하세요. 자동 재개하지 않습니다.')
  return result
}

// These are experiment conditions, not production recommendations.
import { isPostType } from './followup.js'
export function defaultCases() {
  const common = { unit: 'REQUESTS', goal: 100, connectTimeoutMs: 5000, intervalMs: 2000, pollIntervalMs: 5000, maxPolls: 5 }
  return [10000, 30000, 60000].flatMap(requestTimeoutMs => [1, 2, 3].map(concurrency => ({
    ...common, type: 'GET_FIXED', endpoint: 'LIST', requestTimeoutMs, concurrency
  }))).concat(['CREATE_POLL', 'REPEAT_POST'].map(type => ({
    ...common, type, endpoint: 'CREATE', requestTimeoutMs: 30000, concurrency: 1
  })))
}
export function caseProgress(entry, runs) {
  const selected = runs.filter(r => r.caseId === entry.caseId)
  const observed = selected.reduce((n, r) => n + (r.result?.attempts?.length || 0), 0)
  return { observed, remaining: Math.max(0, entry.conditions.goal - observed),
    procedureDone: !!entry.conditions.protocol && selected.length > 0,
    unresolved: selected.some(r => r.recordingStatus === 'RUNNING'), runs: selected }
}
export function nextRequest(conditions, remaining, inputs) {
  if (remaining <= 0) throw new Error('케이스 목표가 채워졌습니다.')
  const durationMs = 120000
  const post = isPostType(conditions.type)
  if (conditions.protocol) {
    if (remaining !== conditions.goal) throw new Error('후속 탐색은 절차를 한 번만 실행합니다. 새 계획으로 명시적으로 재실행하세요.')
    const repeats = ['B01', 'C01', 'C02'].includes(conditions.protocol.family) ? 3 : 1
    return { ...conditions, ...inputs, repeats, callBudget: conditions.goal, durationMs,
      postAcknowledged: post, experimentAcknowledged: true }
  }
  const groups = Math.max(1, Math.floor(durationMs / (conditions.requestTimeoutMs + conditions.intervalMs)))
  const repeats = post ? 1 : Math.min(remaining, 6, groups * conditions.concurrency)
  const maxPolls = conditions.type === 'CREATE_POLL' ? Math.min(conditions.maxPolls, remaining - 1) : conditions.maxPolls
  const callBudget = conditions.type === 'CREATE_POLL' ? 1 + maxPolls : conditions.type === 'REPEAT_POST' ? Math.min(4, remaining) : repeats
  return { ...conditions, ...inputs, repeats, maxPolls, callBudget, durationMs, postAcknowledged: post }
}
export function canContinue(result) {
  return result?.run?.state === 'COMPLETED' && result.attempts?.length > 0 &&
    result.attempts.every(a => a.outcome === 'HTTP_RESPONSE' && a.httpCode >= 200 && a.httpCode < 300 && !a.issues?.length) &&
    result.postComparison?.result !== 'UNKNOWN_NO_REPOST' && result.postComparison?.result !== 'INCOMPLETE_OR_REJECTED'
}
export function caseLabel(c) {
  const p = c.protocol
  const prefix = p ? `${p.family} ${p.variant} ${p.block ? `블록${p.block}/순서${p.order}` : ''} · ` : ''
  return `${prefix}${c.type}/${c.endpoint} · ${c.requestTimeoutMs / 1000}초 · 동시성 ${c.concurrency} · ${p ? '최대' : '목표'} HTTP ${c.goal}건${c.type === 'CREATE_POLL' ? ` · 폴링 ${c.pollIntervalMs / 1000}초` : ''}`
}

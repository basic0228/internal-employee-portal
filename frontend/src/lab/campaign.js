// These are experiment conditions, not production recommendations.
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
    unresolved: selected.some(r => r.recordingStatus === 'RUNNING'), runs: selected }
}
export function nextRequest(conditions, remaining, inputs) {
  if (remaining <= 0) throw new Error('케이스 목표가 채워졌습니다.')
  const durationMs = 120000
  const post = conditions.type !== 'GET_FIXED'
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
  return `${c.type} · ${c.requestTimeoutMs / 1000}초 · 동시성 ${c.concurrency} · 목표 HTTP ${c.goal}건`
}

export const quantile = (sorted, p) => {
  if (!sorted.length) return null
  const h = (sorted.length - 1) * p
  return sorted[Math.floor(h)] + (h - Math.floor(h)) * (sorted[Math.ceil(h)] - sorted[Math.floor(h)])
}
export function statistics(values) {
  const x = values.filter(Number.isFinite).sort((a, b) => a - b)
  const n = x.length
  if (!n) return { n: 0, mean: null, p50: null, p95: null, p99: null, max: null, meanMedianGap: null, outliers: [] }
  const mean = x.reduce((a, b) => a + b, 0) / n
  const q1 = quantile(x, .25), q3 = quantile(x, .75), p50 = quantile(x, .5), h99 = (n - 1) * .99
  return { n, mean, p50, p95: quantile(x, .95), p99: quantile(x, .99), max: x[n - 1],
    meanMedianGap: mean - p50, p95Ratio: p50 ? quantile(x, .95) / p50 : null, maxRatio: p50 ? x[n-1]/p50 : null,
    outliers: x.filter(v => v > q3 + 1.5 * (q3 - q1)),
    tailRanks: [Math.floor(h99) + 1, Math.ceil(h99) + 1], tailValues: [x[Math.floor(h99)], x[Math.ceil(h99)]] }
}
export function summarize(attempts = [], phases = []) {
  const http = attempts.filter(a => a.outcome === 'HTTP_RESPONSE')
  const distribution = field => {
    const groups = new Map()
    for (const row of attempts) {
      const key = String(row[field] ?? 'HTTP 미관측')
      groups.set(key, (groups.get(key) || 0) + 1)
    }
    return [...groups].map(([value, count]) => ({ value, count, n: attempts.length, ratio: attempts.length ? count / attempts.length * 100 : null }))
  }
  return { n: attempts.length, http: statistics(http.map(a => a.elapsedMs)),
    success: statistics(http.filter(a => a.httpCode >= 200 && a.httpCode < 300).map(a => a.elapsedMs)),
    httpError: statistics(http.filter(a => a.httpCode >= 400).map(a => a.elapsedMs)),
    abort: statistics(attempts.filter(a => a.outcome !== 'HTTP_RESPONSE').map(a => a.elapsedMs)),
    statuses: distribution('httpCode'), outcomes: distribution('outcome'),
    concurrency: phases.map(phase => {
      const rows = attempts.filter(a => a.concurrency === phase.concurrency)
      const failed = rows.filter(a => a.outcome !== 'HTTP_RESPONSE' || a.httpCode >= 400 || a.issues?.length).length
      return { ...phase, ...statistics(rows.filter(a => a.outcome === 'HTTP_RESPONSE').map(a => a.elapsedMs)),
        n: rows.length, latencyN: rows.filter(a => a.outcome === 'HTTP_RESPONSE').length, failed,
        errorRatio: rows.length ? failed / rows.length * 100 : null, throughput: phase.elapsedMs > 0 ? rows.length / phase.elapsedMs * 1000 : null }
    }) }
}
export function timeoutComparison(attempts, candidate) {
  let exceeds = 0, within = 0, unknown = 0
  if (!Number.isFinite(candidate) || candidate <= 0) return { n: attempts.length, exceeds, within, unknown: attempts.length, httpN: 0, httpExceeds: 0, httpRatio: null }
  const http = attempts.filter(a => a.outcome === 'HTTP_RESPONSE')
  for (const a of attempts) {
    if (a.outcome === 'HTTP_RESPONSE') a.elapsedMs > candidate ? exceeds++ : within++
    else if (a.outcome === 'REQUEST_TIMEOUT' && a.elapsedMs > candidate) exceeds++
    else unknown++
  }
  const httpExceeds = http.filter(a => a.elapsedMs > candidate).length
  return { n: attempts.length, exceeds, within, unknown, httpN: http.length, httpExceeds,
    httpRatio: http.length ? httpExceeds / http.length * 100 : null }
}
export function retryComparison(policy, observed = []) {
  const { retries, initialMs, capMs, attemptMs, deadlineMs, respectRetryAfter } = policy
  if (![retries, initialMs, capMs, attemptMs, deadlineMs].every(Number.isFinite) || !Number.isInteger(retries) || retries < 0 || retries > 10 || attemptMs <= 0 || initialMs < 0 || capMs < initialMs || deadlineMs <= 0) return []
  const eligible = observed.filter(a => (policy.statuses || []).includes(a.httpCode))
  const knownHints = eligible.map(a => a.retryAfter?.headerMs ?? a.retryAfter?.bodyMs).filter(Number.isFinite)
  const hint = knownHints.length ? Math.max(...knownHints) : 0
  return ['FIXED', 'EXPONENTIAL'].map(mode => {
    const delays = Array.from({ length: retries }, (_, i) => Math.max(
      mode === 'FIXED' ? initialMs : Math.min(capMs, initialMs * 2 ** i), respectRetryAfter ? hint : 0))
    const scenarioMs = (retries + 1) * attemptMs + delays.reduce((a,b) => a+b,0)
    return { mode, attempts: retries + 1, delays, scenarioMs, deadlineMs, scenarioBudgetMs: Math.min(deadlineMs, scenarioMs),
      observedHintN: knownHints.length, eligibleN: eligible.length, sampleN: observed.length, hintMs: hint,
      caveat: respectRetryAfter ? '관측 최대 안내값 반복 적용 시나리오. 미래 Retry-After는 알 수 없어 무제한 최악값은 계산 불가. 전체 deadline에서 중단.' : '요청 전체 상한과 대기값을 가정한 모델. 실제 성공률: 추가 실험 필요.' }
  })
}
export function pollingComparison(workflows, intervalMs, maxPolls, durationMs) {
  const eligible = workflows.filter(w => w.postInitialStatus !== 'clear' && w.postInitialStatus !== 'flagged' && Number.isFinite(w.businessDurationMs) && w.businessDurationMs >= 0)
  if (!(intervalMs > 0 && Number.isInteger(maxPolls) && maxPolls > 0 && durationMs > 0)) return { n: 0, excluded: workflows.length, rows: [], calls: statistics([]), delay: statistics([]) }
  const rows = eligible.map(w => {
    const calls = Math.max(1, Math.ceil(w.businessDurationMs / intervalMs))
    const foundAt = calls * intervalMs
    return { checkRef: w.checkRef, businessDurationMs: w.businessDurationMs, calls, delayMs: foundAt - w.businessDurationMs,
      found: calls <= maxPolls && foundAt <= durationMs }
  })
  return { n: rows.length, excluded: workflows.length - rows.length, rows,
    calls: statistics(rows.filter(r => r.found).map(r => r.calls)), delay: statistics(rows.filter(r => r.found).map(r => r.delayMs)) }
}
export const display = value => Number.isFinite(value) ? value.toFixed(2) : '측정 전 / 추가 실험 필요'

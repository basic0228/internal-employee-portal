import { statistics } from './analysis.js'

// Planned ceilings, never fabricated observations or production recommendations.
export const followupPlans = [
  ['B01', 'B01 · 정상 이력 확인 (최대 3 GET)'],
  ['T01', 'T01 · LIST/유효 DETAIL 60초 관찰 (최대 4 GET)'],
  ['R01', 'R01 · GET 500 → 2초/10초 후 1회 (최대 12 HTTP)'],
  ['R02', 'R02 · GET 503 → 안내 후 1회 (최대 2 HTTP)'],
  ['P01', 'P01 · 기존 pending 조회 (최대 7 GET)'],
  ['P02', 'P02 · 신규 POST 1 + 상세 GET ≤6 (최대 7 HTTP)'],
  ['C01', 'C01 · LIST 균형 3블록 (최대 27 GET)'],
  ['R03', 'R03 · Fixed/Exponential 추가 ≤2회 (최대 18 HTTP)'],
  ['R04', 'R04 · GET timeout → 10초 후 60초 GET (최대 2 HTTP)'],
  ['P04', 'P04 · 새 업무별 폴링 2/5/10초 (최대 21 HTTP, POST ≤3)'],
  ['C02', 'C02 · 최종 DETAIL 균형 3블록 (최대 27 GET)'],
  ['C03', 'C03 · LIST 500 포함 동시성 1/2/3 비교 (최대 9 GET, POST 없음)']
].map(([id, label]) => ({ id, label }))

export function followupCases(family) {
  const base = { type: 'GET_FIXED', endpoint: 'LIST', unit: 'REQUESTS', goal: 1, concurrency: 1,
    connectTimeoutMs: 5000, requestTimeoutMs: 30000, intervalMs: 2000, pollIntervalMs: 5000, maxPolls: 6 }
  const entry = (overrides = {}, variant = 'DEFAULT', block = 0, order = 0) => ({ ...base, ...overrides,
    protocol: { family, variant, block, order } })
  if (family === 'B01') return [entry({ goal: 3 })]
  if (family === 'C03') return [1, 2, 3].map(order => entry({ goal: 3, concurrency: order }, 'DEFAULT', 1, order))
  if (family === 'T01') return ['LIST', 'DETAIL'].flatMap(endpoint => [entry({ endpoint, requestTimeoutMs: 60000 }), entry({ endpoint, requestTimeoutMs: 60000 })])
  if (family === 'R01' || family === 'R03') return [1, 2, 3].flatMap(block =>
    (family === 'R01' ? ['SHORT', 'LONG'] : ['FIXED', 'EXPONENTIAL']).map((variant, index) =>
      entry({ type: 'GET_RETRY', goal: family === 'R01' ? 2 : 3 }, variant, block, index + 1)))
  if (family === 'R02' || family === 'R04') return [entry({ type: 'GET_RETRY', goal: 2 })]
  if (family === 'P01') return [entry({ type: 'POLL_EXISTING', goal: 7 })]
  if (family === 'P02') return [entry({ type: 'CREATE_POLL', endpoint: 'CREATE', goal: 7 })]
  if (family === 'P04') return [2000, 5000, 10000].map(pollIntervalMs => entry({ type: 'CREATE_POLL', endpoint: 'CREATE', goal: 7, pollIntervalMs }))
  if (family === 'C01' || family === 'C02') return [1, 2, 3].flatMap(block => [1, 2, 3].map(order =>
    entry({ goal: 3, concurrency: (block + order - 2) % 3 + 1, endpoint: family === 'C01' ? 'LIST' : 'DETAIL' }, 'DEFAULT', block, order)))
  throw new Error('지원하지 않는 후속 계획입니다.')
}
export const isPostType = type => ['CREATE_POLL', 'REPEAT_POST'].includes(type)
export function retryEvidence(result) {
  const operations = result?.logicalOperations || []
  const eligible = operations.filter(o => o.eligibleInitialFailure)
  const started = eligible.filter(o => o.retriesStarted > 0)
  const recovered = eligible.filter(o => o.recovered === true)
  return { n: operations.length, eligibleN: eligible.length, followupN: started.length,
    recoveredN: recovered.length, deferredN: eligible.filter(o => o.result === 'RETRY_DEFERRED_BUDGET').length,
    recoveryPercent: eligible.length && started.length ? 100 * recovered.length / eligible.length : null,
    retryCalls: (result?.attempts || []).filter(a => a.retryIndex > 0).length }
}
export function comparisonRows(runs) {
  return runs.filter(r => r.result?.run?.protocol).map(({ result }) => {
    const p = result.run.protocol, a = result.attempts || []
    const successes = a.filter(x => x.outcome === 'HTTP_RESPONSE' && x.httpCode >= 200 && x.httpCode < 300 && !x.issues?.length)
    const http = a.filter(x => x.outcome === 'HTTP_RESPONSE')
    const elapsedMs = Date.parse(result.run.updatedAt) - Date.parse(result.run.startedAt)
    return { runId: result.run.runId, family: p.family, variant: p.variant, block: p.block, order: p.order,
      endpoint: result.run.endpoint, concurrency: result.run.conditions.maxConcurrency, n: a.length,
      successN: successes.length, httpN: http.length, failedN: a.length - successes.length,
      successLatency: statistics(successes.map(x => x.elapsedMs)), maxClientCallsInFlight: result.run.maxClientCallsInFlight,
      widths: [...new Set(a.map(x => x.groupWidth))].join(','), queue: statistics(a.map(x => x.queueWaitMs)),
      elapsedMs, observedThroughput: elapsedMs > 0 ? successes.length * 1000 / elapsedMs : null,
      state: result.run.state, reason: result.run.reason, retry: retryEvidence(result) }
  })
}

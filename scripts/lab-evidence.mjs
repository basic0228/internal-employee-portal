// Offline analysis of preserved exports. This module never calls an API.
import { statistics, timeoutComparison } from '../frontend/src/lab/analysis.js'

export function evidenceForPlan(plan, runs) {
  if (!Number.isInteger(plan.requestedAttempts) || plan.requestedAttempts < 1 || !Array.isArray(plan.runIds)) throw new Error('Invalid measurement plan')
  if (new Set(plan.runIds).size !== plan.runIds.length) throw new Error('Duplicate plan runId')
  const selected = plan.runIds.map(id => {
    const run = runs.find(r => r.run.runId === id)
    if (!run || run.run.source !== 'EXTERNAL') throw new Error('Plan requires a preserved EXTERNAL run')
    if (!['GET_BASELINE','GET_CONCURRENCY'].includes(run.run.type) || run.attempts.some(a=>!['LIST','DETAIL'].includes(a.endpoint) || a.retryIndex!==0)) throw new Error('This plan analyzer supports GET measurements without application retries')
    return run
  })
  const observations = selected.flatMap(r => r.attempts)
  if (observations.length > plan.requestedAttempts) throw new Error('Plan call budget exceeded')
  const distribution = field => [...new Set(observations.map(a => a[field] ?? 'UNOBSERVED'))].map(value => ({
    value, count: observations.filter(a => (a[field] ?? 'UNOBSERVED') === value).length, n: observations.length,
    ratio: observations.filter(a => (a[field] ?? 'UNOBSERVED') === value).length / observations.length
  }))
  const groups = new Map()
  for (const r of selected) {
    const key = JSON.stringify([r.run.source, r.run.type, r.run.endpoint, r.run.revision, r.run.javaVersion,
      r.run.client, r.run.specSha256, Object.keys(r.run.conditions).sort().map(k => [k, r.run.conditions[k]])])
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(r)
  }
  const cohorts = [...groups.values()].map(group => {
    const attempts = group.flatMap(r => r.attempts)
    const success = attempts.filter(a => a.outcome === 'HTTP_RESPONSE' && a.httpCode >= 200 && a.httpCode < 300)
    const errors = attempts.filter(a => a.outcome === 'HTTP_RESPONSE' && a.httpCode >= 400)
    return { runIds: group.map(r => r.run.runId), conditions: group[0].run.conditions, n: attempts.length,
      success: statistics(success.map(a => a.elapsedMs)), httpErrors: statistics(errors.map(a => a.elapsedMs)),
      timeoutCandidates: [5000, 10000, 15000, 20000, 30000].map(ms => ({ ms, ...timeoutComparison(attempts, ms),
        successN: success.length, successExceeds: success.filter(a => a.elapsedMs > ms).length })) }
  })
  const hints = observations.filter(a => Number.isFinite(a.retryAfter?.headerMs) || Number.isFinite(a.retryAfter?.bodyMs))
  return { planId: plan.planId, requestedAttempts: plan.requestedAttempts, observedAttempts: observations.length,
    remainingAttempts: plan.requestedAttempts - observations.length, state: plan.state, statuses: distribution('httpCode'),
    outcomes: distribution('outcome'), cohorts, hints: hints.map(a => ({ sequence: a.sequence, httpCode: a.httpCode, ...a.retryAfter })),
    stops: selected.filter(r => r.run.reason).map(r => ({ runId: r.run.runId, reason: r.run.reason })),
    recoveryChecks: (plan.recoveryRunIds || []).map(id => {
      const run=selected.find(r=>r.run.runId===id)
      if (!run || run.attempts.length!==1) throw new Error('Recovery check must reference a preserved single attempt')
      return { runId:id, n:1, outcome:run.attempts[0].outcome, httpCode:run.attempts[0].httpCode }
    }),
    retryExperimentN: 0, postN: observations.filter(a => a.endpoint === 'CREATE').length,
    businessDurationN: selected.flatMap(r => r.workflows || []).filter(w => Number.isFinite(w.businessDurationMs)).length,
    limitations: ['Runs are sequential and may depend on prior responses; no independent-sample claim.',
      'Successful latency and error-response latency are separate. Aborted elapsed is not completed latency.',
      'Client batch transitions and physical connection reuse are not instrumented.',
      'Waiting followed by a new measurement is not a controlled retry-success experiment.'] }
}

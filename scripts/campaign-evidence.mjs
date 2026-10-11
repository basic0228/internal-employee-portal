// Offline evidence only. No API calls, invented observations or operational setting changes.
import { statistics, timeoutComparison, retryComparison } from '../frontend/src/lab/analysis.js'

export function analyzeCampaign(bundle) {
  if (!Array.isArray(bundle?.plan?.cases) || !Array.isArray(bundle.runs)) throw new Error('Invalid campaign export')
  const seenRuns = new Set(), seenAttempts = new Set()
  const runs = bundle.runs.map(row => {
    const result = row.result
    if (!result?.run || result.run.source !== 'EXTERNAL' || !['COMPLETED', 'STOPPED'].includes(result.run.state)) throw new Error('Final EXTERNAL observations required')
    if (seenRuns.has(result.run.runId)) throw new Error('Duplicate run')
    seenRuns.add(result.run.runId)
    if (!bundle.plan.cases.some(c => c.caseId === result.run.caseId)) throw new Error('Unknown case')
    for (const a of result.attempts) {
      if (seenAttempts.has(a.attemptId)) throw new Error('Duplicate attempt')
      seenAttempts.add(a.attemptId)
      if (!Number.isFinite(a.elapsedMs) || a.elapsedMs < 0) throw new Error('Invalid elapsed time')
      if (a.retryIndex !== 0) throw new Error('This exploratory analysis requires application Retry OFF')
    }
    return result
  })
  const cohorts = [], cases = bundle.plan.cases.map((c, index) => {
    const group = runs.filter(r => r.run.caseId === c.caseId)
    const attempts = group.flatMap(r => r.attempts)
    if (attempts.length > c.conditions.goal) throw new Error('Case budget exceeded')
    const id = `E${String(index + 1).padStart(2, '0')}`
    for (const endpoint of new Set(attempts.map(a => a.endpoint))) {
      const aa = attempts.filter(a => a.endpoint === endpoint)
      const completed = aa.filter(a => a.outcome === 'HTTP_RESPONSE')
      const success = completed.filter(a => a.httpCode >= 200 && a.httpCode < 300)
      const errors = completed.filter(a => a.httpCode >= 400)
      const cohort = { id, caseId: c.caseId, type: c.conditions.type, endpoint, conditions: c.conditions,
        runIds: group.map(r => r.run.runId), n: aa.length, http: statistics(completed.map(a => a.elapsedMs)),
        success: statistics(success.map(a => a.elapsedMs)), errors: statistics(errors.map(a => a.elapsedMs)),
        unknownN: aa.length - completed.length,
        statuses: Object.fromEntries([200,201,400,401,404,429,500,503].map(code=>[code,aa.filter(a=>a.httpCode===code).length])),
        outcomes: Object.fromEntries(['HTTP_RESPONSE','REQUEST_TIMEOUT','CONNECT_TIMEOUT','CANCELLED','NETWORK_ERROR'].map(key=>[key,aa.filter(a=>a.outcome===key).length])),
        timeoutCandidates: [10000,30000,60000].map(ms => ({ms,...timeoutComparison(aa,ms),successN:success.length,successExceeds:success.filter(a=>a.elapsedMs>ms).length})) }
      cohorts.push(cohort)
    }
    return {id,...c,runIds:group.map(r=>r.run.runId),observed:attempts.length,remaining:c.conditions.goal-attempts.length}
  })
  const all = runs.flatMap(r => r.attempts)
  const hints = runs.flatMap(r=>r.attempts.filter(a=>a.retryAfter?.headerMs!=null || a.retryAfter?.bodyMs!=null).map(a=>({runId:r.run.runId,attemptId:a.attemptId,...a.retryAfter})))
  const workflows = runs.flatMap(r=>(r.workflows||[]).map(w=>({runId:r.run.runId,type:r.run.type,...w})))
  const duplicates = runs.filter(r=>r.run.type==='REPEAT_POST').map(r=>({runId:r.run.runId,...r.postComparison}))
  // Assumed 30s request limit, 1s initial delay, 4s cap, 120s overall deadline.
  // These scenarios are engineering bounds, not measured Retry success probabilities.
  const retryModels = [0,1,2].flatMap(retries=>retryComparison({retries,initialMs:1000,capMs:4000,attemptMs:30000,
    deadlineMs:120000,respectRetryAfter:true,statuses:[500,503]},all).map(m=>({retries,...m})))
  const retryWithoutHint = [0,1,2].flatMap(retries=>retryComparison({retries,initialMs:1000,capMs:4000,attemptMs:30000,
    deadlineMs:120000,respectRetryAfter:false,statuses:[500,503]},all).map(m=>({retries,...m})))
  return {campaignId:bundle.plan.campaignId,cases,cohorts,runs,attempts:all,hints,workflows,duplicates,retryModels,retryWithoutHint,
    counts:{cases:cases.length,runs:runs.length,attempts:all.length,http:all.filter(a=>a.outcome==='HTTP_RESPONSE').length,
      remaining:cases.reduce((n,c)=>n+c.remaining,0),retryExperimentN:0,businessDurationN:workflows.filter(w=>Number.isFinite(w.businessDurationMs)).length,
      pendingToFinalN:workflows.filter(w=>Number.isFinite(w.observedPendingToFinalMs)).length}}
}

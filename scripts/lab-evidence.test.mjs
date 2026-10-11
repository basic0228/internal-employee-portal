import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { evidenceForPlan } from './lab-evidence.mjs'

const plan=JSON.parse(await fs.readFile(new URL('../docs/measurements/browser-100-plan.json',import.meta.url),'utf8'))
const runs=await Promise.all(plan.runIds.map(id=>fs.readFile(new URL(`../docs/measurements/raw/lab-${id}.json`,import.meta.url),'utf8').then(JSON.parse)))
test('requested count never becomes a measured sample; run-local unstarted differs from plan remaining',()=>{
  const e=evidenceForPlan(plan,runs)
  assert.equal(e.observedAttempts,3); assert.equal(e.remainingAttempts,97)
  assert.equal(runs.reduce((s,r)=>s+r.run.notStarted,0),3)
})
test('503 latency is separated from successful latency and timeouts remain unknown',()=>{
  const e=evidenceForPlan(plan,runs)
  assert.equal(e.cohorts.length,2)
  assert.equal(e.cohorts.reduce((s,c)=>s+c.success.n,0),0)
  assert.equal(e.cohorts.reduce((s,c)=>s+c.httpErrors.n,0),1)
  assert.equal(e.cohorts[0].httpErrors.p99,1166.10174)
  assert.equal(e.cohorts.reduce((s,c)=>s+c.timeoutCandidates[0].unknown,0),2)
})
test('HTTP unobserved stays separate from HTTP 503 and header absence is preserved',()=>{
  const e=evidenceForPlan(plan,runs)
  assert.equal(e.statuses.find(s=>s.value==='UNOBSERVED').count,2)
  assert.equal(e.statuses.find(s=>s.value===503).count,1)
  assert.equal(e.hints.length,1); assert.equal(e.hints[0].headerMs,null); assert.equal(e.hints[0].bodyMs,30000)
})
test('recovery COMPLETED state does not mean HTTP success or establish retry success rate',()=>{
  const e=evidenceForPlan(plan,runs)
  assert.equal(runs[1].run.state,'COMPLETED')
  assert.equal(e.recoveryChecks[0].outcome,'REQUEST_TIMEOUT')
  assert.equal(e.retryExperimentN,0)
})
test('duplicate, missing and undersized budgets cannot inflate evidence',()=>{
  assert.throws(()=>evidenceForPlan({...plan,runIds:[...plan.runIds,plan.runIds[0]]},runs),/Duplicate/)
  assert.throws(()=>evidenceForPlan(plan,runs.slice(1)),/preserved/)
  assert.throws(()=>evidenceForPlan({...plan,requestedAttempts:2},runs),/budget exceeded/)
})

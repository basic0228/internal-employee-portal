import { test } from 'node:test'
import assert from 'node:assert/strict'
import { followupPlans, followupCases, retryEvidence, comparisonRows } from './followup.js'
import { nextRequest, caseProgress } from './campaign.js'
import { markdown } from './exports.js'
test('core pilots reserve 55 HTTP with P02 replacing P01, without growing old plans', () => {
  const ids = ['B01','T01','R01','R02','P01','C01']
  assert.equal(ids.flatMap(followupCases).reduce((n,c) => n+c.goal,0),55)
  assert.equal(followupCases('P02')[0].goal, followupCases('P01')[0].goal)
  assert.ok(followupPlans.every(p => followupCases(p.id).length <= 12))
})
test('all followup procedures fit server ceilings and reserve the complete protocol', () => {
  for (const p of followupPlans) for (const c of followupCases(p.id)) {
    const r = nextRequest(c,c.goal,{})
    assert.equal(r.callBudget,c.goal);assert.ok(r.callBudget <= 20)
    assert.equal(r.experimentAcknowledged,true)
    assert.equal(r.postAcknowledged,['CREATE_POLL','REPEAT_POST'].includes(c.type))
    assert.equal(r.durationMs,120000)
    assert.throws(() => nextRequest(c,c.goal-1,{}))
  }
})
test('balanced blocks have fixed orders and equal planned three-request levels', () => {
  const c = followupCases('C01')
  assert.deepEqual(c.map(x=>x.concurrency),[1,2,3,2,3,1,3,1,2])
  assert.ok(c.every(x=>x.goal===3 && x.requestTimeoutMs===30000))
  assert.ok(followupCases('C02').every(x=>x.endpoint==='DETAIL'))
})
test('a success without retry completes the procedure with unused HTTP capacity', () => {
  const c = followupCases('R01')[0]
  const p = caseProgress({caseId:'one',conditions:c},[{caseId:'one',recordingStatus:'COMPLETED',result:{attempts:[{}]}}])
  assert.equal(p.observed,1);assert.equal(p.remaining,1);assert.equal(p.procedureDone,true)
})
test('recovery uses eligible initial operations, never ordinary error counts', () => {
  assert.equal(retryEvidence({attempts:[{httpCode:500}]}).recoveryPercent,null)
  const r = retryEvidence({logicalOperations:[{eligibleInitialFailure:true,retriesStarted:1,recovered:true},
    {eligibleInitialFailure:true,retriesStarted:0,result:'RETRY_DEFERRED_BUDGET'}, {eligibleInitialFailure:false,retriesStarted:0}],
    attempts:[{retryIndex:0},{retryIndex:1}]})
  assert.equal(r.n,3);assert.equal(r.eligibleN,2);assert.equal(r.followupN,1);assert.equal(r.recoveryPercent,50)
  assert.equal(r.deferredN,1);assert.equal(r.retryCalls,1)
  assert.equal(retryEvidence({logicalOperations:[{eligibleInitialFailure:true,retriesStarted:0}]}).recoveryPercent,null)
})
test('comparison preserves partial groups and separates failed and normal latency', () => {
  const rows = comparisonRows([{result:{run:{runId:'r',protocol:{family:'C01',variant:'DEFAULT',block:1,order:2},
    endpoint:'LIST',conditions:{maxConcurrency:2},startedAt:'2026-01-01T00:00:00Z',updatedAt:'2026-01-01T00:00:10Z',maxClientCallsInFlight:2},
    attempts:[{outcome:'HTTP_RESPONSE',httpCode:200,elapsedMs:100,queueWaitMs:1,groupWidth:2},
      {outcome:'HTTP_RESPONSE',httpCode:500,elapsedMs:1000,queueWaitMs:2,groupWidth:1}]}}])
  assert.equal(rows[0].n,2);assert.equal(rows[0].failedN,1);assert.equal(rows[0].successLatency.n,1)
  assert.equal(rows[0].successLatency.p95,100);assert.equal(rows[0].widths,'2,1');assert.equal(rows[0].observedThroughput,.1)
})
test('Markdown exports actual logical retry evidence independently from candidate models', () => {
  const result={run:{runId:'r',source:'LOCAL_MOCK',protocol:followupCases('R01')[0].protocol},attempts:[],
    logicalOperations:[{eligibleInitialFailure:true,retriesStarted:1,recovered:true,result:'RECOVERED_WITHIN_BUDGET',waits:[]}],workflows:[]}
  const s = markdown(result,[],{retry:{},retryStatuses:''})
  assert.ok(s.includes('"eligibleN":1'));assert.ok(s.includes('"recoveryPercent":100'))
  assert.ok(s.includes('외부 실측으로 제출 금지'))
})

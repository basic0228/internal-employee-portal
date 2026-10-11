// Offline verification and descriptive statistics only. No external requests.
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { statistics } from '../frontend/src/lab/analysis.js'

const path=process.argv[2]
assert(path, 'Provide the exported C03 campaign JSON path')
const raw=await readFile(path,'utf8'), campaign=JSON.parse(raw)
function scan(value) {
  if(!value || typeof value!=='object') return
  for(const [key,child] of Object.entries(value)) {
    assert(!/^(candidateKey|x-candidate-key|password|employeeId|checkId|firstName|lastName|dateOfBirth|responseBody)$/i.test(key),'Private field')
    scan(child)
  }
}
scan(campaign)
assert.equal(campaign.plan.maxHttpCalls,9)
assert.deepEqual(campaign.plan.cases.map(c=>c.conditions.protocol.order),[1,2,3])
const levels=campaign.plan.cases.map(({caseId,conditions:c})=> {
  assert.equal(c.protocol.family,'C03');assert.equal(c.type,'GET_FIXED');assert.equal(c.endpoint,'LIST')
  assert.equal(c.goal,3);assert.equal(c.concurrency,c.protocol.order);assert.equal(c.requestTimeoutMs,30000)
  const records=campaign.runs.filter(r=>r.caseId===caseId)
  assert(records.length<=1,'Repeated procedure')
  if(!records.length) return {concurrency:c.concurrency,n:0,state:'UNSTARTED',normalLatency:statistics([]),errorLatency:statistics([])}
  const {result:r,stale,recordingStatus}=records[0]
  assert(!stale && recordingStatus!=='RUNNING');assert.equal(r.run.source,'EXTERNAL')
  assert.equal(r.run.protocol.family,'C03');assert.equal(r.run.conditions.maxConcurrency,c.concurrency)
  assert(r.attempts.length<=3);assert.equal(r.logicalOperations.length,0)
  assert(r.attempts.every(a=>a.endpoint==='LIST' && a.retryIndex===0))
  const normal=r.attempts.filter(a=>a.outcome==='HTTP_RESPONSE' && a.httpCode===200 && !a.issues.length)
  const errors=r.attempts.filter(a=>a.outcome==='HTTP_RESPONSE' && a.httpCode>=400)
  const counts={}
  for(const a of r.attempts) {const k=a.outcome==='HTTP_RESPONSE'?String(a.httpCode):a.outcome;counts[k]=(counts[k]||0)+1}
  return {concurrency:c.concurrency,n:r.attempts.length,runId:r.run.runId,revision:r.run.revision,
    state:r.run.state,reason:r.run.reason,counts,normalN:normal.length,errorN:r.attempts.length-normal.length,
    errorPercent:r.attempts.length?100*(r.attempts.length-normal.length)/r.attempts.length:null,
    normalLatency:statistics(normal.map(a=>a.elapsedMs)),errorLatency:statistics(errors.map(a=>a.elapsedMs)),
    maxClientCallsInFlight:r.run.maxClientCallsInFlight,groupWidths:r.attempts.map(a=>a.groupWidth),
    startedAt:r.run.startedAt,endedAt:r.run.updatedAt}
})
const n=levels.reduce((sum,l)=>sum+l.n,0)
assert(n<=9);assert.equal(new Set(levels.filter(l=>l.n).map(l=>l.revision)).size,1)
const summary={campaignId:campaign.plan.campaignId,sha256:createHash('sha256').update(raw).digest('hex'),
  quantileMethod:'TYPE_7',actualHttp:n,actualPost:0,priorSuiteHttp:10,totalSuiteHttp:10+n,remainingSuiteHttp:130-10-n,
  levels,limitations:'Small ordered pilot; normal and error latency separated; no retry recovery estimate; interrupted latency is censored; time and load confound concurrency comparison; not a safe production upper bound'}
await writeFile(path.replace(/\.json$/,'-c03-summary.json'),JSON.stringify(summary,null,2),'utf8')
console.log(JSON.stringify(summary,null,2))

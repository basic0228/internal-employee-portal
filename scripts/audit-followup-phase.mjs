// Offline audit of one resumed phase; no HTTP calls and no synthetic observations.
import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { statistics } from '../frontend/src/lab/analysis.js'
import { retryEvidence } from '../frontend/src/lab/followup.js'

const path=resolve(process.argv[2])
const manifest=JSON.parse(await readFile(path,'utf8')), folder=dirname(path)
function scan(value) {
  if(!value || typeof value!=='object') return
  for(const [key,child] of Object.entries(value)) {
    assert(!/^(candidateKey|x-candidate-key|password|employeeId|checkId|firstName|lastName|dateOfBirth|responseBody)$/i.test(key),'Private field')
    scan(child)
  }
}
scan(manifest)
const seen=new Set(), runs=[], hashes=[]
for(const entry of manifest.runs) {
  assert(!seen.has(entry.runId),'Duplicate run');seen.add(entry.runId)
  const raw=await readFile(resolve(folder,`lab-${entry.runId}.json`),'utf8'), r=JSON.parse(raw)
  scan(r);assert.equal(r.run.runId,entry.runId);assert.equal(r.run.source,'EXTERNAL')
  assert(['COMPLETED','STOPPED'].includes(r.run.state));assert.equal(r.run.campaignId,entry.campaignId)
  const c=JSON.parse(await readFile(resolve(folder,`campaign-${entry.campaignId}.json`),'utf8'))
  scan(c);const stored=c.runs.find(s=>s.runId===entry.runId)
  assert(stored && !stored.stale && stored.recordingStatus!=='RUNNING');assert.deepEqual(stored.result,r)
  runs.push(r);hashes.push({runId:entry.runId,sha256:createHash('sha256').update(raw).digest('hex')})
}
const a=runs.flatMap(r=>r.attempts), post=a.filter(x=>x.endpoint==='CREATE').length
assert.equal(a.length,manifest.actualHttp);assert.equal(post,manifest.actualPost)
assert(manifest.priorActualHttp+a.length<=manifest.approvedHttpCeiling)
assert(manifest.priorActualPost+post<=manifest.approvedPostCeiling)
const cohorts=[]
for(const run of runs) {
  for(const outcome of [...new Set(run.attempts.map(x=>`${x.endpoint}/${x.outcome}/${x.httpCode ?? 'UNOBSERVED'}`))]) {
    const group=run.attempts.filter(x=>`${x.endpoint}/${x.outcome}/${x.httpCode ?? 'UNOBSERVED'}`===outcome)
    cohorts.push({runId:run.run.runId,protocol:run.run.protocol,revision:run.run.revision,
      cohort:outcome,metric:group[0].outcome==='HTTP_RESPONSE'?'completed_http_ms':'interruption_elapsed_ms',
      latency:statistics(group.map(x=>x.elapsedMs))})
  }
}
const result={phaseId:manifest.phaseId,actualHttp:a.length,actualPost:post,
  totalActualHttp:manifest.priorActualHttp+a.length,totalActualPost:manifest.priorActualPost+post,
  remainingHttp:manifest.approvedHttpCeiling-manifest.priorActualHttp-a.length,
  remainingPost:manifest.approvedPostCeiling-manifest.priorActualPost-post,
  cohorts,retries:runs.filter(r=>r.run.type==='GET_RETRY').map(r=>({runId:r.run.runId,...retryEvidence(r)})),
  workflows:runs.flatMap(r=>r.workflows.map(w=>({runId:r.run.runId,...w}))),hashes,
  limitation:'Error-dependent stopping and unequal cohorts; no causal recovery estimate or fabricated unstarted observations'}
await writeFile(path.replace(/\.json$/,'-summary.json'),JSON.stringify(result,null,2),'utf8')
console.log(JSON.stringify(result,null,2))

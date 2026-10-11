import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { analyzeCampaign } from './campaign-evidence.mjs'
const bundle = JSON.parse(await fs.readFile(new URL('../docs/measurements/raw/campaign-683583f2-e476-4ef1-8ab0-8c961a2f0128.json',import.meta.url),'utf8'))
test('actual observations reconcile without counting unstarted requests',()=>{
  const e=analyzeCampaign(bundle)
  assert.equal(e.counts.attempts,34);assert.equal(e.counts.http,31);assert.equal(e.counts.remaining,1066)
  assert.equal(e.cohorts.reduce((n,c)=>n+c.n,0),34)
  assert.equal(e.counts.businessDurationN,0);assert.equal(e.counts.pendingToFinalN,0)
  assert.equal(e.duplicates.filter(d=>d.historyReconciled).length,1)
})
test('uncertain latency stays unknown and successful slow GET remains visible',()=>{
  const e=analyzeCampaign(bundle)
  const cancelled=e.cohorts.find(c=>c.id==='E06')
  assert.equal(cancelled.timeoutCandidates[0].unknown,2)
  const slow=e.cohorts.find(c=>c.id==='E09')
  assert.equal(slow.timeoutCandidates[0].successExceeds,1)
  assert.equal(slow.timeoutCandidates[1].successExceeds,0)
  assert.equal(e.cohorts.find(c=>c.id==='E01').success.p99,null)
})
test('duplicate IDs and unfinished runs cannot inflate evidence',()=>{
  const d=structuredClone(bundle);d.runs.push(d.runs[0]);assert.throws(()=>analyzeCampaign(d),/Duplicate run/)
  const p=structuredClone(bundle);p.runs[0].result.run.state='RUNNING';assert.throws(()=>analyzeCampaign(p),/Final/)
  const a=structuredClone(bundle);a.runs[1].result.attempts[0].attemptId=a.runs[0].result.attempts[0].attemptId
  assert.throws(()=>analyzeCampaign(a),/Duplicate attempt/)
  const retry=structuredClone(bundle);retry.runs[0].result.attempts[0].retryIndex=1
  assert.throws(()=>analyzeCampaign(retry),/Retry OFF/)
})
test('Retry bounds respect the observed hint without inventing success rates',()=>{
  const e=analyzeCampaign(bundle)
  assert.equal(e.retryModels.find(m=>m.retries===1).scenarioMs,90000)
  assert.equal(e.retryModels.find(m=>m.retries===2).scenarioMs,150000)
  assert.equal(e.counts.retryExperimentN,0)
  assert.equal(e.retryWithoutHint.find(m=>m.retries===2&&m.mode==='EXPONENTIAL').scenarioMs,93000)
})

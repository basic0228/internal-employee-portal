// Offline submission index: original files are read only; no API/DB/config writes.
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { statistics } from '../frontend/src/lab/analysis.js'
import { retryEvidence } from '../frontend/src/lab/followup.js'
import { analyzeCampaign } from './campaign-evidence.mjs'

const root=path.resolve('docs/measurements/raw'), output=path.resolve('docs/measurements/integrated')
const files=(await fs.readdir(root)).sort(), records=new Map(), sources=[]
function scan(v) {
  if(!v || typeof v!=='object')return
  for(const [k,x] of Object.entries(v)) {
    assert(!/^(candidateKey|x-candidate-key|password|password_hash|employeeId|checkId|firstName|lastName|dateOfBirth|responseBody|email|phone)$/i.test(k),`Private field: ${k}`)
    scan(x)
  }
}
function add(result,file) {
  if(!result)return
  assert.equal(result.run.source,'EXTERNAL')
  assert(['COMPLETED','STOPPED'].includes(result.run.state),'Only final runs may be included')
  assert(!result.incomplete,'Partial stream cannot become a full observation')
  scan(result)
  const existing=records.get(result.run.runId)
  if(existing) {
    assert.deepEqual(existing.result.attempts,result.attempts,'Conflicting snapshots')
    assert.deepEqual(existing.result.run.conditions,result.run.conditions,'Conflicting conditions')
    existing.sources.push(file)
  } else records.set(result.run.runId,{result,sources:[file]})
}
for(const file of files.filter(f=>/^lab-.*\.json$/.test(f)||/^campaign-[a-f0-9-]+\.json$/.test(f))) {
  const bytes=await fs.readFile(path.join(root,file)),data=JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''))
  scan(data)
  sources.push({file,sha256:createHash('sha256').update(bytes).digest('hex')})
  if(data.run)add(data,file)
  else for(const stored of data.runs||[]) {
    assert(!stored.stale,'Stale snapshot requires manual reconciliation')
    add(stored.result,file)
  }
}
const initialId='683583f2-e476-4ef1-8ab0-8c961a2f0128'
const initial=analyzeCampaign(JSON.parse(await fs.readFile(path.join(root,`campaign-${initialId}.json`),'utf8')))
assert.equal(initial.counts.attempts,34)
const initialIds=new Set(initial.runs.map(r=>r.run.runId)), followupIds=new Set()
for(const file of ['followup-suite-20261012-d19050c7.json','followup-remaining-20261012.json','followup-sequence-20261012.json']) {
  const m=JSON.parse(await fs.readFile(path.join(root,file),'utf8'))
  for(const r of m.runs)followupIds.add(r.runId)
}
for(const id of ['e67172ec-09be-4ea8-8f90-6d410aebdce5','8c014f8a-8e13-4d71-98d3-9ce96cbe978f'])followupIds.add(id)
const seenAttempts=new Set(), cohorts=[], runs=[], attempts=[], logical=[], workflows=[]
for(const [id,entry] of [...records].sort((a,b)=>a[1].result.run.startedAt.localeCompare(b[1].result.run.startedAt))) {
  const {result:r}=entry, group=initialIds.has(id)?'INITIAL_11_CASES':followupIds.has(id)?'FOLLOWUP_130_HTTP':'OTHER_EXPLORATORY'
  const run={...r.run,evidenceGroup:group,sourceFiles:entry.sources,actualN:r.attempts.length}
  runs.push(run)
  for(const a of r.attempts) {
    const aid=a.attemptId || `${id}/${a.sequence}`
    assert(!seenAttempts.has(aid),'Duplicate attempt across runs');seenAttempts.add(aid)
    assert(Number.isFinite(a.elapsedMs)&&a.elapsedMs>=0)
    attempts.push({...a,runId:id,evidenceGroup:group,revision:run.revision,protocol:run.protocol})
  }
  for(const key of new Set(r.attempts.map(a=>`${a.endpoint}/${a.outcome}/${a.httpCode??'UNOBSERVED'}`))) {
    const a=r.attempts.filter(a=>`${a.endpoint}/${a.outcome}/${a.httpCode??'UNOBSERVED'}`===key)
    cohorts.push({runId:id,evidenceGroup:group,revision:run.revision,protocol:run.protocol||null,
      conditions:run.conditions,endpoint:a[0].endpoint,outcome:a[0].outcome,httpCode:a[0].httpCode??null,
      metric:a[0].outcome==='HTTP_RESPONSE'?'completed_http_ms':'interruption_elapsed_ms',statistics:statistics(a.map(x=>x.elapsedMs)),
      timeoutCandidates:a[0].outcome==='HTTP_RESPONSE'&&a[0].httpCode<300?[10000,30000,60000].map(ms=>({ms,n:a.length,exceeds:a.filter(x=>x.elapsedMs>ms).length})):[]})
  }
  if(r.logicalOperations?.length)logical.push({runId:id,protocol:run.protocol,...retryEvidence(r)})
  for(const w of r.workflows||[])workflows.push({runId:id,...w})
}
const distribution=a=>({attempts:a.length,completedHttp:a.filter(x=>x.outcome==='HTTP_RESPONSE').length,
  post:a.filter(x=>x.endpoint==='CREATE').length,codes:Object.fromEntries([...new Set(a.filter(x=>x.outcome==='HTTP_RESPONSE').map(x=>x.httpCode))].sort().map(c=>[c,a.filter(x=>x.httpCode===c&&x.outcome==='HTTP_RESPONSE').length])),
  interruptions:Object.fromEntries([...new Set(a.filter(x=>x.outcome!=='HTTP_RESPONSE').map(x=>x.outcome))].map(c=>[c,a.filter(x=>x.outcome===c).length]))})
const groups=Object.fromEntries(['INITIAL_11_CASES','FOLLOWUP_130_HTTP','OTHER_EXPLORATORY'].map(g=>[g,{runs:runs.filter(r=>r.evidenceGroup===g).length,...distribution(attempts.filter(a=>a.evidenceGroup===g))}]))
assert.equal(groups.FOLLOWUP_130_HTTP.attempts,18);assert.equal(groups.FOLLOWUP_130_HTTP.post,2)
assert.equal(groups.INITIAL_11_CASES.attempts,34)
const evidence={asOf:'2026-10-12 KST',method:'TYPE_7',scope:'Unique final EXTERNAL runId; repeated campaign/run exports are not added twice. Screen-only observation excluded.',
  totals:{runs:runs.length,...distribution(attempts)},groups,runs,attempts,cohorts,logical,workflows,sources,
  initialCases:initial.cases,initialCohorts:initial.cohorts,duplicates:initial.duplicates,
  workflowCount:workflows.length,
  initialPendingN:workflows.filter(w=>w.postInitialStatus==='pending').length,
  initialTerminalN:workflows.filter(w=>['clear','flagged'].includes(w.postInitialStatus)).length,
  finalBusinessN:workflows.filter(w=>Number.isFinite(w.businessDurationMs)).length,
  pendingToFinalN:workflows.filter(w=>Number.isFinite(w.observedPendingToFinalMs)).length}
scan(evidence)
await fs.mkdir(output,{recursive:true});await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify(evidence,null,2)+'\n')
const n=x=>x==null?'TODO':x.toFixed(3)
const lines=['# 통합 원본 인덱스','',`기준: ${evidence.asOf}. 원본 파일 ${sources.length}개에서 고유 종료 실행 ${runs.length}개, 실제 시도 ${attempts.length}개를 중복 제거했다. 이는 기록 재고이며 전체를 단일 성능 표본으로 합산하지 않는다.`,
  '', '| 구분 | 실행 n | 실제 시도 n | 완료 HTTP n | POST n |','|---|---:|---:|---:|---:|',
  ...Object.entries(groups).map(([g,s])=>`| ${g} | ${s.runs} | ${s.attempts} | ${s.completedHttp} | ${s.post} |`),
  '', '## 상세 GET 원본 기반 통계','', 'LIST와 별도다. 같은 run/HTTP 상태별로 분리했다. 지연은 ms, Type7이며 소표본 상위 백분위수는 장기 tail 보장이 아니다.',
  '', '| run ID | 구분 | HTTP/전송 결과 | n | 평균 | p50 | p95 | p99 | 최대 |','|---|---|---|---:|---:|---:|---:|---:|---:|',
  ...cohorts.filter(c=>c.endpoint==='DETAIL').map(c=>`| ${c.runId} | ${c.evidenceGroup} | ${c.httpCode??c.outcome} | ${c.statistics.n} | ${n(c.statistics.mean)} | ${n(c.statistics.p50)} | ${n(c.statistics.p95)} | ${n(c.statistics.p99)} | ${n(c.statistics.max)} |`),
  '', '비HTTP 행은 중단까지 경과시간이며 완료 응답 지연이 아니다. 정상 업무 시간·최종 발견과 혼합하지 않는다.',
  '', '## 실행 추적','', '| run ID | 구분 | 프로토콜/유형 | 실제 n | 상태 | 원본 |','|---|---|---|---:|---|---|',
  ...runs.map(r=>`| ${r.runId} | ${r.evidenceGroup} | ${r.protocol?.family||r.type} ${r.protocol?.variant||''} | ${r.actualN} | ${r.state}/${r.reason||'-'} | ${r.sourceFiles.map(f=>`[JSON](../raw/${f})`).join(', ')} |`),
  '', '정확한 설정·리비전·시각·原본 SHA256은 evidence.json에 포함한다. 화면 관측만 남은 실행은 정밀 통계에 포함하지 않았다. 오류 의존 중단·서로 다른 대상과 시각 때문에 고정 독립 표본으로 해석하지 않는다.']
await fs.writeFile(path.join(output,'INDEX.md'),lines.join('\n').replace('原본','원본')+'\n')
console.log(JSON.stringify({totals:evidence.totals,groups,logical,finalBusinessN:evidence.finalBusinessN,pendingToFinalN:evidence.pendingToFinalN,detailCohorts:cohorts.filter(c=>c.endpoint==='DETAIL'),duplicatePairs:initial.duplicates},null,2))

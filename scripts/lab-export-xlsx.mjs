// Offline only. Rebuild from verified Lab JSONs; no synthetic rows or external API calls.
import fs from 'node:fs/promises'
import path from 'node:path'
import crypto from 'node:crypto'
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool'
import { statistics } from '../frontend/src/lab/analysis.js'
import { evidenceForPlan } from './lab-evidence.mjs'

const args = process.argv.slice(2)
const noRender = args.includes('--no-render')
const planIndex = args.indexOf('--plan')
const planPath = planIndex < 0 ? null : args[planIndex + 1]
if (planIndex >= 0 && !planPath) throw new Error('--plan requires a JSON path')
const [output, ...inputs] = args.filter((a,i)=>a!=='--no-render' && i!==planIndex && (planIndex<0 || i!==planIndex+1))
if (!output || !inputs.length) throw new Error('Usage: node scripts/lab-export-xlsx.mjs output.xlsx raw1.json [raw2.json ...]')
const runs = [], seen = new Set()
for (const file of inputs) {
  const bytes = await fs.readFile(file)
  const text = bytes.toString('utf8').replace(/^\uFEFF/, '')
  if (/"(?:candidateKey|password|password_hash|firstName|lastName|dateOfBirth|employeeId|checkId|email|phone)"\s*:/.test(text)) throw new Error('Unexpected sensitive field in input')
  const data = JSON.parse(text)
  if (!data.run?.runId || !['EXTERNAL','LOCAL_MOCK'].includes(data.run.source) || !Array.isArray(data.attempts) || data.incomplete) throw new Error('Expected a completed-stream Lab result, not a partial event file')
  if (seen.has(data.run.runId)) throw new Error('Duplicate runId would inflate sample counts')
  seen.add(data.run.runId)
  for (const a of data.attempts) if (!Number.isFinite(a.elapsedMs) || a.elapsedMs < 0) throw new Error('Invalid observed elapsedMs')
  runs.push({ ...data, file: path.basename(file), sha: crypto.createHash('sha256').update(bytes).digest('hex') })
}
const wb = Workbook.create()
const evidence = planPath ? evidenceForPlan(JSON.parse(await fs.readFile(planPath, 'utf8')), runs) : null
const summary = wb.worksheets.add('Summary')
const policy = evidence ? wb.worksheets.add('Evidence') : null
const candidates = wb.worksheets.add('Candidates')
const attempts = wb.worksheets.add('Attempts')
const provenance = wb.worksheets.add('Runs')
const col = n => { let s=''; for (n++; n; n=Math.floor((n-1)/26)) s=String.fromCharCode(65+(n-1)%26)+s; return s }
function table(sheet, row, headers, rows, widths) {
  const last = row + rows.length
  sheet.getRange(`A${row}:${col(headers.length-1)}${last}`).values = [headers, ...rows]
  const used = sheet.getRange(`A${row}:${col(headers.length-1)}${Math.max(last,row)}`)
  used.format.font = { name:'Arial', size:10, color:'#1F2937' }
  used.format.rowHeight = 23
  sheet.getRange(`A${row}:${col(headers.length-1)}${row}`).format = { fill:'#243B53', font:{name:'Arial',size:10,bold:true,color:'#FFFFFF'}, rowHeight:30 }
  for (let i=0;i<headers.length;i++) sheet.getRange(`${col(i)}${row}:${col(i)}${last}`).format.columnWidth=widths?.[i] || 18
  sheet.showGridLines=false
  return last
}
function title(sheet, text) {
  sheet.getRange('A2').values=[[text]]
  sheet.getRange('A2').format.font={name:'Arial',size:14,bold:true,color:'#243B53'}
  sheet.getRange('A2').format.rowHeight=28
}
const rows=[]
const blocks=new Map()
for (const data of runs) {
  const first = rows.length + 2
  for (const a of data.attempts) rows.push([
    data.run.runId,a.sequence,a.endpoint,a.concurrency,a.outcome,a.httpCode,a.elapsedMs,a.queueWaitMs,
    a.requestTimeoutMs,a.outcome==='HTTP_RESPONSE'?a.elapsedMs:null,
    a.outcome!=='HTTP_RESPONSE'?a.elapsedMs:null,a.startedAt,a.endedAt,a.responseBytes,
    a.httpVersion,a.status||null,(a.issues||[]).join('|'),a.retryAfter?.headerMs??null,a.retryAfter?.bodyMs??null,
    (a.issues||[]).length
  ])
  blocks.set(data.run.runId,{first,last:rows.length+1})
}
if (!rows.length) throw new Error('At least one real observation is required')
table(attempts,1,['runId','sequence','Endpoint','concurrency','outcome','HTTP code','elapsed ms','queue ms','configured limit ms','completed HTTP ms','abort elapsed ms','startedAt UTC (raw)','endedAt UTC (raw)','response bytes','HTTP version','business status','contract issues','Retry-After header ms','Retry-After body ms','contract issue n'],rows,[42,12,12,14,23,12,17,15,20,20,20,32,32,17,17,18,26,24,24,18])
attempts.getRange(`G2:K${rows.length+1}`).setNumberFormat('#,##0.000000')
attempts.freezePanes.freezeRows(1)
attempts.freezePanes.freezeColumns(1)
// UTC text is deliberately preserved: converting to Excel date serials would lose source nanoseconds.
const runRows = runs.map(d => [d.run.runId,d.run.source,d.run.type,d.run.endpoint,d.run.state,d.run.reason,
  d.run.conditions.connectTimeoutMs,d.run.conditions.requestTimeoutMs,d.run.conditions.durationMs,
  d.run.conditions.repeats,d.run.conditions.maxConcurrency,d.run.conditions.intervalMs,d.run.conditions.callBudget,
  d.run.javaVersion,d.run.revision,d.run.specVersion,d.run.specSha256,d.file,d.sha,
  d.collection?.batchId||null,d.collection?.round||null,
  d.run.plannedUpperBound,d.run.started,d.run.finished,d.run.notStarted])
table(provenance,1,['runId','source','type','Endpoint','state','stop reason','connect limit ms','request limit ms','run limit ms','repeats per phase','max concurrency','batch gap ms','call budget','Java','revision','spec version','spec SHA256','source JSON','source SHA256','collection batch','round','planned upper bound','started n','finished n','not started n'],runRows,[42,16,24,12,18,25,19,19,18,19,19,18,16,18,44,16,68,56,68,42,12,22,16,16,18])
provenance.freezePanes.freezeRows(1)
provenance.freezePanes.freezeColumns(1)
title(summary,'API Reliability Lab — 실제 관측 요약')
summary.getRange('A4').values=[['조건과 실행별로 구분합니다. 완료 HTTP 지연에서 타임아웃·네트워크 중단을 제외합니다.']]
const summaryRows=[]
for (const d of runs) for (const endpoint of new Set(d.attempts.map(a=>a.endpoint))) {
  for (const concurrency of new Set(d.attempts.filter(a=>a.endpoint===endpoint).map(a=>a.concurrency))) summaryRows.push([d.run.runId,d.run.source,endpoint,concurrency,null,null,null,null,null,null,null,null,null,null,null,null,null])
}
table(summary,6,['runId','source','Endpoint','concurrency','all n','HTTP n','abort n','HTTP 200 n','error n','error rate','mean ms','p50 ms','p95 ms','p99 ms','max ms','mean − median','abort mean ms'],summaryRows,[42,16,12,14,12,12,12,15,12,15,17,17,17,17,17,19,20])
const independent=[]
for(let i=0;i<summaryRows.length;i++) {
  const r=i+7, row=summaryRows[i], b=blocks.get(row[0])
  const rr = c=>`'Attempts'!$${c}$${b.first}:$${c}$${b.last}`
  const criteria=`${rr('C')},C${r},${rr('D')},D${r}`
  const countHttp=`COUNTIFS(${criteria},${rr('E')},"HTTP_RESPONSE")`
  const countAbort=`COUNTIFS(${criteria},${rr('E')},"<>HTTP_RESPONSE")`
  summary.getRange(`E${r}:J${r}`).formulas=[[
    `=COUNTIFS(${criteria})`,`=${countHttp}`,`=${countAbort}`,
    `=COUNTIFS(${criteria},${rr('F')},200)`,
    `=COUNTIFS(${criteria},${rr('E')},"<>HTTP_RESPONSE")+COUNTIFS(${criteria},${rr('E')},"HTTP_RESPONSE",${rr('F')},">=400")+COUNTIFS(${criteria},${rr('E')},"HTTP_RESPONSE",${rr('F')},"<400",${rr('T')},">0")`,
    `=IF(E${r}=0,"TODO",I${r}/E${r})`
  ]]
  const observed = runs.find(d=>d.run.runId===row[0]).attempts.filter(a=>a.endpoint===row[2]&&a.concurrency===row[3])
  const s=statistics(observed.filter(a=>a.outcome==='HTTP_RESPONSE').map(a=>a.elapsedMs))
  // An empty issue string must never classify HTTP 200 as an error in the cached workbook.
  const failed=observed.filter(a=>a.outcome!=='HTTP_RESPONSE'||a.httpCode>=400||(a.issues||[]).length>0).length
  const counts=summary.getRange(`E${r}:J${r}`).values[0]
  const expected=[observed.length,s.n,observed.length-s.n,observed.filter(a=>a.httpCode===200).length,failed,failed/observed.length]
  if(counts.some((value,index)=>value!==expected[index])) throw new Error('Summary count/rate formulas disagree with raw outcomes')
  // Type 7 cohort quantiles are calculated by the same tested JS module as the UI.
  // Export is a reproducible snapshot: rerun after changing observations, never edit the raw table as new measurements.
  summary.getRange(`K${r}:O${r}`).values=[[s.mean??'TODO',s.p50??'TODO',s.p95??'TODO',s.p99??'TODO',s.max??'TODO']]
  summary.getRange(`P${r}`).formulas=[[`=IF(F${r}=0,"TODO",K${r}-L${r})`]]
  summary.getRange(`Q${r}`).formulas=[[`=IF(G${r}=0,"TODO",SUMIFS(${rr('K')},${criteria},${rr('E')},"<>HTTP_RESPONSE")/G${r})`]]
  const aborted=observed.filter(a=>a.outcome!=='HTTP_RESPONSE')
  const expectedAbortMean=aborted.length ? aborted.reduce((sum,a)=>sum+a.elapsedMs,0)/aborted.length : 'TODO'
  const actualAbortMean=summary.getRange(`Q${r}`).values[0][0]
  if(actualAbortMean!==expectedAbortMean) throw new Error('Abort mean formula disagrees with raw elapsed times')
  independent.push({runId:row[0],endpoint:row[2],concurrency:row[3],attempts:observed.length,httpN:s.n,...s})
}
summary.getRange(`J7:J${6+summaryRows.length}`).setNumberFormat('0.0%')
summary.getRange(`K7:Q${6+summaryRows.length}`).setNumberFormat('#,##0.00')
const noteRow=9+summaryRows.length
summary.getRange(`A${noteRow}:A${noteRow+4}`).values=[
  ['Type 7: h=(n−1)p 선형 보간. n=1의 p95/p99는 같은 한 관측값이며 tail 분포의 증거가 아닙니다.'],
  ['타임아웃이 제외된 완료 HTTP 통계는 지연을 낙관적으로 보일 수 있습니다. 중단 경과시간은 완료 지연이 아닙니다.'],
  ['HTTP 수신에는 4xx/5xx도 포함합니다. 오류는 전송 실패·HTTP ≥400·명세 검사 issue 중 하나이며 중복 합산하지 않습니다.'],
  ['백분위수·평균은 원본 기반 JS 계산 스냅샷입니다. 원본이 늘면 빌더를 다시 실행하세요.'],
  ['POST·업무 완료·재시도 성공률·동시성 상한은 해당 실험이 없으면 추가 실험 필요입니다.']
]
// A collection cohort is descriptive only: require matching recorded conditions and versions.
// Keep the individual runs above, including aborted samples from different conditions.
const collections=new Map()
for(const d of runs) {
  if(!d.collection?.batchId) continue
  const conditions=Object.keys(d.run.conditions).sort().map(k=>[k,d.run.conditions[k]])
  const key=JSON.stringify([d.collection.batchId,d.collection.targetGroupAlias,d.run.source,d.run.type,
    d.run.endpoint,d.run.revision,d.run.javaVersion,d.run.client,d.run.specVersion,d.run.specSha256,
    d.collection.roundGapMs,conditions])
  if(!collections.has(key)) collections.set(key,[])
  collections.get(key).push(d)
}
const batches=[]
for(const group of collections.values()) {
  if(group.length<2) continue
  const d=group[0], observed=group.flatMap(x=>x.attempts), completed=observed.filter(a=>a.outcome==='HTTP_RESPONSE')
  const s=statistics(completed.map(a=>a.elapsedMs))
  batches.push({batchId:d.collection.batchId,runIds:group.map(x=>x.run.runId),statistics:s})
}
let summaryLast=noteRow+4
if(batches.length) {
  const heading=noteRow+6
  summary.getRange(`A${heading}`).values=[['같은 수집 묶음·기록 조건·버전의 완료 HTTP 기술 통계 (원본별 표는 위에 유지)']]
  const batchRows=batches.map(b=>[b.batchId,b.runIds.length,b.statistics.n,b.statistics.mean,b.statistics.p50,
    b.statistics.p95,b.statistics.p99,b.statistics.max,b.statistics.meanMedianGap,b.runIds.join(' | ')])
  summaryLast=table(summary,heading+1,['collection batch','runs n','HTTP n','mean ms','p50 ms','p95 ms','p99 ms','max ms','mean − median','linked runIds'],batchRows,[42,14,14,18,18,18,18,18,20,86])+2
  summary.getRange(`D${heading+2}:I${summaryLast-2}`).setNumberFormat('#,##0.00')
  summary.getRange(`A${summaryLast}:A${summaryLast+1}`).values=[
    ['p95/p99는 Type 7 보간이며 tail 보장·재시도 성공률·원인 추정이 아닙니다. 표본 수와 함께 해석하세요.'],
    ['기록 조건 일치는 실제 호출 간격·연결 상태·독립성을 보장하지 않습니다. 서버 원본 시각도 확인하세요.']
  ]
  summaryLast++
}
if (evidence) {
  summary.getRange('A3').values=[[`${evidence.requestedAttempts}회 계획: 실제 ${evidence.observedAttempts}회 · 미실행 ${evidence.remainingAttempts}회 · ${evidence.state}`]]
  title(policy,'설정 항목별 실측 근거와 추가 검증')
  policy.getRange('A3').values=[['선택값은 TODO입니다. 성공 응답·오류 응답·전송 중단 표본을 구분하세요.']]
  const row=5
  const items=[
    ['요청 시간 제한',evidence.observedAttempts,'조건별 성공/오류 지연과 중단 표본을 분리','Candidates 및 조건별 후보 비교','TODO'],
    ['연결 시간 제한',0,'연결/TLS 단계 시간 미계측','추가 단계 계측 필요','TODO'],
    ['상태코드',evidence.observedAttempts,evidence.statuses.map(s=>`${s.value}: ${s.count}/${s.n}`).join(', '),'응답 분포이며 미래 장애율 보장 아님','TODO'],
    ['Retry-After',evidence.hints.length,evidence.hints.map(h=>`HTTP ${h.httpCode}, header ${h.headerMs??'없음'}, body ${h.bodyMs??'없음'} ms`).join('; ')||'미관측','헤더 우선/body fallback·재개 후 결과 별도 기록','TODO'],
    ['대기 후 회복 확인',evidence.recoveryChecks.length,evidence.recoveryChecks.map(r=>`${r.outcome}, HTTP ${r.httpCode??'미관측'}`).join(', ')||'미실행','단일 회복 관측이며 재시도 성공률로 일반화 불가','TODO'],
    ['재시도 성공률/횟수/Backoff',0,'통제된 재시도 실험 없음','추가 실험 필요','TODO'],
    ['Polling 간격/최대 대기',evidence.businessDurationN,'업무 완료시간 표본과 HTTP 지연 구분','POST/pending/최종 상태 관측 필요','TODO'],
    ['동일 사번 POST 중복',evidence.postN,'이번 계획은 LIST GET 전용','승인된 POST 실험 필요','TODO'],
    ['최대 동시 요청',evidence.observedAttempts,'동시성 1만 측정','동시성 2/3 동일 조건 비교 필요','TODO'],
    ['실험 중단',evidence.stops.length,evidence.stops.map(s=>s.reason).join(', ')||'없음','계획 미실행을 표본에 포함하지 않음','TODO']
  ]
  const policyLast=table(policy,row,['근거 항목','관련 n','실제 근거','한계/다음 검증','선택값'],items,[36,12,60,60,16])
  policy.getRange(`C${row+1}:D${policyLast}`).format.wrapText=true
  policy.getRange(`A${row+1}:E${policyLast}`).format.autofitRows()
  policy.getRange(`A${policyLast+2}`).values=[['503 수신 지연은 정상 응답 성능이 아닙니다. 중단·조건부 재개에 따른 표본 선택 영향을 기록합니다.']]
  const cohortHeading=summaryLast+2
  summary.getRange(`A${cohortHeading}`).values=[['이번 계획의 설정별 정상/오류 HTTP 지연 (성공 표본이 없으면 TODO)']]
  const focusedRows=evidence.cohorts.map(c=>[c.runIds.join(' | '),'LIST',c.conditions.maxConcurrency,c.n,
    c.success.n,c.httpErrors.n,c.n-c.success.n-c.httpErrors.n,c.success.mean??'TODO',c.success.p50??'TODO',
    c.success.p95??'TODO',c.success.p99??'TODO',c.success.max??'TODO',c.httpErrors.mean??'TODO',
    c.httpErrors.p95??'TODO',c.httpErrors.p99??'TODO',c.httpErrors.max??'TODO'])
  summaryLast=table(summary,cohortHeading+1,['runIds','Endpoint','동시성','전체 n','정상 HTTP n','오류 HTTP n','중단 n',
    '정상 평균 ms','정상 p50 ms','정상 p95 ms','정상 p99 ms','정상 최대 ms','오류 평균 ms','오류 p95 ms','오류 p99 ms','오류 최대 ms'],focusedRows,[42,16,12,14,12,12,12,15,12,15,17,17,17,17,17,19])+1
  summary.getRange(`H${cohortHeading+2}:P${summaryLast-1}`).setNumberFormat('#,##0.00')
}
title(candidates,'Timeout 후보 비교와 판단 TODO')
candidates.getRange('A4').values=[['B열에 비교할 전체 요청 제한(ms)을 입력하세요. 빈칸은 미선택입니다. 서버 운영 설정을 변경하지 않습니다.']]
const candidateRows=summaryRows.map(row=>[row[0],null,row[2],row[3],null,null,null,null,null,null,'TODO','TODO','TODO'])
table(candidates,6,['runId','candidate ms','Endpoint','concurrency','HTTP n','HTTP exceeds n','HTTP exceed rate','abort unknown n','p95 ms','p99 ms','선택 이유','반대 선택 장점','부적절해질 상황'],candidateRows,[42,18,12,14,15,19,20,20,17,17,40,40,40])
for(let i=0;i<candidateRows.length;i++) {
  const r=i+7,b=blocks.get(candidateRows[i][0]),rr=c=>`'Attempts'!$${c}$${b.first}:$${c}$${b.last}`
  candidates.getRange(`E${r}:J${r}`).formulas=[[
    `='Summary'!F${r}`,
    `=IF(B${r}="","TODO",COUNTIFS(${rr('C')},C${r},${rr('D')},D${r},${rr('E')},"HTTP_RESPONSE",${rr('G')},">"&B${r}))`,
    `=IF(B${r}="","TODO",IF(E${r}=0,"TODO",F${r}/E${r}))`,
    `='Summary'!G${r}`,`='Summary'!M${r}`,`='Summary'!N${r}`
  ]]
  // Exercise the editable threshold against the real cohort, then restore its unselected state.
  candidates.getRange(`B${r}`).values=[[1]]
  const source=runs.find(d=>d.run.runId===candidateRows[i][0]).attempts.filter(a=>a.endpoint===candidateRows[i][2]&&a.concurrency===candidateRows[i][3])
  const expected=source.filter(a=>a.outcome==='HTTP_RESPONSE'&&a.elapsedMs>1).length
  if(candidates.getRange(`F${r}`).values[0][0]!==expected) throw new Error('Candidate formula failed input-change verification')
  candidates.getRange(`B${r}`).values=[[null]]
  if(candidates.getRange(`F${r}`).values[0][0]!=='TODO') throw new Error('Blank candidate must remain TODO, not zero')
}
candidates.getRange(`B7:B${6+candidateRows.length}`).format.fill='#FFF3CD'
candidates.getRange(`G7:G${6+candidateRows.length}`).setNumberFormat('0.0%')
candidates.getRange(`I7:J${6+candidateRows.length}`).setNumberFormat('#,##0.00')
candidates.getRange(`A${9+candidateRows.length}:A${12+candidateRows.length}`).values=[
  ['중단 표본은 완료 지연 미확정입니다. elapsed에는 타이머·취소 오버헤드가 있어 후보 초과로 단정하지 않습니다.'],
  ['Retry 성공률: 추가 실험 필요. Retry-After 정책: 실제 안내 응답과 재시도 실험 필요.'],
  ['Polling: 실제 POST 및 완료 업무 시각 관측 필요. 동시성: 같은 실행 조건의 단계별 실험 필요.'],
  ['선택한 설정값과 근거는 사용자 확인 전 TODO입니다. DECISIONS.md 또는 Spring 설정에 자동 적용하지 않습니다.']
]
if(evidence) {
  const heading=14+candidateRows.length
  candidates.getRange(`A${heading}`).values=[['이번 계획의 고정 후보 재분류 — 운영 선택값이 아님. 중단은 미확정.']]
  const fixedRows=evidence.cohorts.flatMap(c=>c.timeoutCandidates.map(t=>[c.runIds.join(' | '),t.ms,'LIST',
    c.conditions.maxConcurrency,t.successN,t.successExceeds,t.successN?t.successExceeds/t.successN:'TODO',t.unknown,
    c.httpErrors.n,t.httpExceeds-t.successExceeds]))
  const last=table(candidates,heading+1,['runIds','후보 ms','Endpoint','동시성','정상 HTTP n','정상 초과 n',
    '정상 초과율','중단 미확정 n','오류 HTTP n','오류 초과 n'],fixedRows,[42,18,12,14,15,19,20,20,17,17])
  candidates.getRange(`G${heading+2}:G${last}`).setNumberFormat('0.0%')
}
wb.recalculate()
console.log((await wb.inspect({kind:'table',range:`Summary!A6:Q${6+summaryRows.length}`,include:'values,formulas',tableMaxRows:10,tableMaxCols:17,maxChars:2500})).ndjson)
const errors=await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#NUM!|#NULL!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:20},maxChars:2000})
console.log(errors.ndjson)
if(!/matched 0 entries/.test(errors.ndjson)) throw new Error('Formula error scan requires investigation before export')
const outputDir=path.dirname(path.resolve(output)); await fs.mkdir(outputDir,{recursive:true})
const xlsx=await SpreadsheetFile.exportXlsx(wb); await xlsx.save(path.resolve(output))
for(const [name,range] of noRender ? [] : [['Summary',`A2:J${summaryLast}`],...(policy?[['Evidence','A2:E17']]:[]),['Candidates',`A2:J${evidence?25+candidateRows.length:12+candidateRows.length}`],['Attempts',`A1:K${Math.min(rows.length+1,14)}`],['Runs',`A1:M${Math.min(runRows.length+1,12)}`]]) {
  const preview=await wb.render({sheetName:name,range,scale:1,format:'png'})
  await fs.writeFile(path.join(outputDir,`${name}-preview.png`),new Uint8Array(await preview.arrayBuffer()))
}
await fs.writeFile(path.join(outputDir,'lab-export-validation.json'),JSON.stringify({sourceFiles:runs.map(d=>({file:d.file,sha256:d.sha,runId:d.run.runId,source:d.run.source})),cohorts:independent,collections:batches,quantileMethod:'TYPE_7',visualVerification:noRender?'PENDING renderer unavailable':'Previews generated; visual review required',generatedAt:new Date().toISOString()},null,2))
if(evidence) await fs.writeFile(path.join(outputDir,'lab-plan-evidence.json'),JSON.stringify(evidence,null,2))
console.log(`Saved ${path.resolve(output)}; runs=${runs.length}; observedAttempts=${rows.length}. No external requests.`)

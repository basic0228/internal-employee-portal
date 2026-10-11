// Offline export. Run integrate-submission-evidence.mjs first; no external calls.
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool'
const e=JSON.parse(await fs.readFile('docs/measurements/integrated/evidence.json','utf8'))
const out=path.resolve('outputs/01a124de-b755-76c1-a212-a325ffee9c34/submission')
await fs.mkdir(out,{recursive:true})
const w=Workbook.create(), sheets=Object.fromEntries(['Summary','Candidates','Cohorts','Attempts','Runs'].map(n=>[n,w.worksheets.add(n)]))
const col=n=>{let s='';for(n++;n;n=Math.floor((n-1)/26))s=String.fromCharCode(65+(n-1)%26)+s;return s}
function table(s,row,headers,rows,widths) {
  const last=row+rows.length,end=col(headers.length-1)
  s.getRange(`A${row}:${end}${last}`).values=[headers,...rows]
  s.getRange(`A${row}:${end}${last}`).format={font:{name:'Arial',size:10,color:'#243B53'},rowHeight:25,verticalAlignment:'center'}
  s.getRange(`A${row}:${end}${row}`).format={fill:'#243B53',font:{name:'Arial',size:10,bold:true,color:'#FFFFFF'},rowHeight:32,horizontalAlignment:'center'}
  widths.forEach((x,i)=>s.getRange(`${col(i)}${row}:${col(i)}${last}`).format.columnWidth=headers[i]==='run ID'?46:headers[i].includes('UTC')?45:x)
  s.showGridLines=false
  return last
}
function text(s,row,t){s.getRange(`A${row}`).values=[[t]];s.getRange(`A${row}`).format.font={name:'Arial',size:10,color:'#526579'};s.getRange(`A${row}`).format.rowHeight=25}
for(const [n,s]of Object.entries(sheets)){text(s,2,n==='Summary'?'API 실측 통합 근거자료':n);s.getRange('A2').format.font={name:'Arial',size:14,bold:true,color:'#243B53'}}
const a=sheets.Attempts, rows=e.attempts.map(x=>[x.runId,x.evidenceGroup,x.sequence,x.endpoint,x.outcome,x.httpCode??null,x.elapsedMs,x.queueWaitMs,x.requestTimeoutMs,x.concurrency,x.retryIndex,x.groupWidth??null,x.retryAfter?.headerMs??null,x.retryAfter?.bodyMs??null,x.issues?.length??0,`UTC ${x.startedAt}`,`UTC ${x.endedAt}`,x.revision])
table(a,5,['run ID','구분','순서','Endpoint','전송 결과','HTTP','경과 ms','큐 ms','요청 제한 ms','동시성','retry index','묶음 크기','헤더 대기 ms','본문 대기 ms','계약 issue n','시작 UTC','종료 UTC','리비전'],rows,[40,27,10,15,22,10,18,15,19,12,15,15,19,19,19,34,34,42]);a.getRange(`G6:I${5+rows.length}`).setNumberFormat('0.000');a.freezePanes.freezeRows(5);a.freezePanes.freezeColumns(1)
const refs=c=>`Attempts!$${c}$6:$${c}$${5+rows.length}`
const s=sheets.Summary;text(s,4,'2026-10-12 KST. 고유 종료 EXTERNAL 실행만 집계. 전체 수치는 기록 재고이며 단일 성능 표본이 아님.')
table(s,6,['집단','실행 n','실제 시도 n','완료 HTTP n','POST n','중단 관측 n'],Object.entries(e.groups).map(([g,v])=>[g,v.runs,null,null,null,null]),[29,14,18,20,14,21])
for(let r=7;r<=9;r++)s.getRange(`C${r}:F${r}`).formulas=[[`=COUNTIFS(${refs('B')},A${r})`,`=COUNTIFS(${refs('B')},A${r},${refs('E')},"HTTP_RESPONSE")`,`=COUNTIFS(${refs('B')},A${r},${refs('D')},"CREATE")`,`=C${r}-D${r}`]]
table(s,12,['HTTP 코드','건수','완료 HTTP 분모 n','기록 내 비율'],[200,201,500,503].map(c=>[c,null,null,null]),[29,14,24,20])
for(let r=13;r<=16;r++)s.getRange(`B${r}:D${r}`).formulas=[[`=COUNTIFS(${refs('F')},A${r},${refs('E')},"HTTP_RESPONSE")`,`=SUM(D7:D9)`,`=B${r}/C${r}`]]
s.getRange('D13:D16').setNumberFormat('0.0%')
text(s,18,'HTTP 코드 미관측: 요청 타임아웃8건·취소2건 / 실제 시도65. 중단 경과를 정상 지연으로 바꾸지 않음.')
table(s,20,['판단 대상','실제 근거 n','현재 결과','운영 선택'],[['실제 GET retry',1,'503→30초 대기→500; 회복0/1','TODO'],['pending 최종 발견',0,'최종 상태·업무 시간 미확인','TODO'],['중복 POST 비교 쌍',2,'서로 다른ID2쌍; 이력 대조1쌍','TODO'],['C03 동시성1',3,'200→200→503; 동시성2·3 n=0','TODO']],[29,18,55,16])
text(s,26,'초기34와 후속18은 별도 승인 실행군. 후속 잔여112 HTTP/POST2는 미시행 예산이며 표본이 아님.')
text(s,27,'개별 run·상태별 Type7 통계는 Cohorts. 기존 캠페인 조건별 E01–E11 분석은 제출 문서에서 별도 보존.')
text(s,28,'후보·추가 실험·최종 사용자 결정은 구분. 실제 키·인적사항·응답 본문을 포함하지 않음.')
const c=sheets.Cohorts; text(c,4,'Type7 스냅샷 통계. run/Endpoint/상태별 분리. 원본 추가 시 재생성. n≤2의 p99는 장기 tail 보장이 아님.')
const cr=e.cohorts.map(x=>[x.runId,x.endpoint,x.httpCode??x.outcome,x.metric,x.statistics.n,x.statistics.mean??'TODO',x.statistics.p50??'TODO',x.statistics.p95??'TODO',x.statistics.p99??'TODO',x.statistics.max??'TODO',x.statistics.meanMedianGap??'TODO',x.evidenceGroup])
table(c,6,['run ID','Endpoint','HTTP/전송 결과','측정 의미','n','평균 ms','p50 ms','p95 ms','p99 ms','최대 ms','평균−중앙값 ms','집단'],cr,[40,15,23,27,10,18,18,18,18,18,22,27]);c.getRange(`F7:K${6+cr.length}`).setNumberFormat('0.000');c.freezePanes.freezeRows(6);c.freezePanes.freezeColumns(1)
const p=sheets.Candidates; text(p,4,'후보 입력은 실측이 아님. 초과 수는 정상 완료 관측의 재분류이며 다른 timeout의 성공률 예측이 아님.')
const pr=e.cohorts.filter(x=>x.timeoutCandidates.length).flatMap(x=>x.timeoutCandidates.map(v=>[x.runId,x.endpoint,v.ms,null,null,null]))
const pe=table(p,6,['run ID','Endpoint','후보 ms','정상 완료 n','초과 n','초과 비율'],pr,[40,15,19,20,16,20])
for(let r=7;r<=pe;r++){const crit=`${refs('A')},A${r},${refs('D')},B${r},${refs('E')},"HTTP_RESPONSE",${refs('F')},">=200",${refs('F')},"<300"`;p.getRange(`D${r}:F${r}`).formulas=[[`=COUNTIFS(${crit})`,`=COUNTIFS(${crit},${refs('G')},">"&C${r})`,`=IF(D${r}=0,"TODO",E${r}/D${r})`]]}
p.getRange(`C7:C${pe}`).format.fill='#FFF1C9';p.getRange(`F7:F${pe}`).setNumberFormat('0.0%')
const rr=pe+4;text(p,rr-1,'Retry 공학 상한. 성공률·Fixed/Exponential 우열은 추가 실험 필요. 안내 h는 가정 입력.')
table(p,rr,['추가 횟수 R','방식','요청 상한 T ms','초기 대기 b ms','cap ms','안내 h ms','대기 합 ms','총 상한 W ms'],[0,1,2].flatMap(n=>['FIXED','EXPONENTIAL'].map(mode=>[n,mode,30000,1000,4000,30000,null,null])),[20,20,22,22,19,20,20,22])
for(let r=rr+1;r<=rr+6;r++)p.getRange(`G${r}:H${r}`).formulas=[[`=IF(A${r}=0,0,MAX(D${r},F${r})+IF(A${r}=2,MAX(IF(B${r}="FIXED",D${r},MIN(E${r},2*D${r})),F${r}),0))`,`=(A${r}+1)*C${r}+G${r}`]]
p.getRange(`A${rr+1}:F${rr+6}`).format.fill='#FFF1C9';p.getRange(`A${rr+1}:A${rr+6}`).dataValidation={rule:{type:'whole',operator:'between',formula1:0,formula2:2}}
text(p,rr+8,'W=(R+1)T+Σmax(backoff,h). 큐·저장 여유는 별도. deadline 부족 시 후속 요청을 시작하지 않음.')
const pp=rr+11;table(p,pp,['폴링 간격 P ms','최대 횟수 K','시간 상한 D ms','완료시간 d ms','예상 조회 수','발견 지연 ms','측정 완료 n'],[[2000,6,120000,null,null,null,0],[5000,6,120000,null,null,null,0],[10000,6,120000,null,null,null,0]],[24,22,22,22,22,22,20])
for(let r=pp+1;r<=pp+3;r++)p.getRange(`E${r}:F${r}`).formulas=[[`=IF(D${r}="","추가 실험 필요",MAX(1,ROUNDUP(D${r}/A${r},0)))`,`=IF(D${r}="","추가 실험 필요",E${r}*A${r}-D${r})`]]
p.getRange(`A${pp+1}:D${pp+3}`).format.fill='#FFF1C9';text(p,pp+5,'ceil(d/P) 모델은 즉시 응답·실패 없음 가정. 실제 응답 후 대기는 W=Tpost+K(P+Tget). 업무 d는 미관측.')
p.getRange(`A6:A${pp+5}`).format.columnWidth=46
p.freezePanes.freezeRows(6)
const r=sheets.Runs;text(r,4,'원본 출처와 SHA256은 이 시트에만 기록. 대상 별칭은 실행 범위에서만 의미가 있음.')
table(r,6,['run ID','집단','실제 n','유형','프로토콜','상태','중단 이유','요청 제한 ms','동시성','시작 UTC','원본 파일','SHA256'],e.runs.map(x=>{const f=x.sourceFiles.find(f=>f.startsWith('lab-'))||x.sourceFiles[0];return[x.runId,x.evidenceGroup,x.actualN,x.type,x.protocol?.family||'없음',x.state,x.reason||'',x.conditions.requestTimeoutMs,x.conditions.maxConcurrency||1,`UTC ${x.startedAt}`,f,e.sources.find(s=>s.file===f).sha256]}),[40,27,13,22,16,16,30,21,14,34,55,72]);r.freezePanes.freezeRows(6);r.freezePanes.freezeColumns(1)
w.recalculate()
assert.equal(s.getRange('C7').values[0][0],34);assert.equal(s.getRange('C8').values[0][0],18);assert.equal(s.getRange('C9').values[0][0],13)
assert.equal(s.getRange('C13').values[0][0],55)
const before=p.getRange('E7').values[0][0], original=p.getRange('C7').values[0][0]
p.getRange('C7').values=[[0]];w.recalculate();assert.equal(p.getRange('E7').values[0][0],p.getRange('D7').values[0][0])
p.getRange('C7').values=[[original]];w.recalculate();assert.equal(p.getRange('E7').values[0][0],before)
for(const sheet of Object.values(sheets))for(const row of sheet.getUsedRange().values)for(const v of row)assert(!['#REF!','#VALUE!','#DIV/0!','#NAME?','#NUM!','#N/A'].includes(v),'Formula error')
await fs.writeFile(path.join(out,'inspection.ndjson'),(await w.inspect({kind:'table',range:'Summary!A6:F24',include:'values,formulas',tableMaxRows:24,tableMaxCols:6,maxChars:8000})).ndjson)
const x=await SpreadsheetFile.exportXlsx(w);await x.save(path.join(out,'submission-evidence.xlsx'))
let visual='NATIVE_RENDER_UNAVAILABLE',renderError=null
try {
  if(process.argv.includes('--skip-render'))throw new Error('Native renderer unavailable: Vulkan initialization failed on this host; use read-only layout previews')
  for(const [name,range]of [['Summary','A2:F28'],['Candidates','A2:F14'],['Cohorts','B6:K14'],['Attempts','C5:O14'],['Runs','C6:I14']]){
    const img=await w.render({sheetName:name,range,scale:1,format:'png'});await fs.writeFile(path.join(out,`${name}.png`),new Uint8Array(await img.arrayBuffer()))
  }
  visual='NATIVE_PREVIEWS_READY'
}catch(err){renderError=err.message}
await fs.writeFile(path.join(out,'validation.json'),JSON.stringify({actualRuns:e.totals.runs,actualAttempts:e.totals.attempts,completedHttp:e.totals.completedHttp,formulaErrors:0,candidateEditRestore:'PASS',visual,renderError,nativeExcel:'NOT_TESTED'},null,2))
console.log(JSON.stringify({output:path.join(out,'submission-evidence.xlsx'),visual,renderError,formulaErrors:0,actualAttempts:e.totals.attempts}))

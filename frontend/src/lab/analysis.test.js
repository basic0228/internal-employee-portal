import test from 'node:test'
import assert from 'node:assert/strict'
import { quantile, statistics, summarize, timeoutComparison, retryComparison, pollingComparison } from './analysis.js'
import { configuration, csv, markdown } from './exports.js'

test('Type 7 empty, single, even median and tails', () => {
  assert.equal(statistics([]).p99, null)
  assert.equal(statistics([7]).p99, 7)
  assert.equal(quantile([1,2,3,4], .5), 2.5)
  assert.equal(quantile([1,2,3,4], .95), 3.8499999999999996)
  assert.equal(statistics([1,2,3,100]).outliers.length, 1)
})
test('latency excludes censored responses but codes and errors use all attempt denominators', () => {
  const rows = [{ outcome:'HTTP_RESPONSE', httpCode:200, elapsedMs:10 }, { outcome:'REQUEST_TIMEOUT', httpCode:200, elapsedMs:100 }]
  const s = summarize(rows)
  assert.equal(s.n, 2); assert.equal(s.http.n,1); assert.equal(s.abort.n,1)
  assert.equal(s.statuses[0].ratio,100); assert.equal(s.outcomes[0].ratio,50)
})
test('timeout candidates preserve unknowns instead of using timeout as completed latency', () => {
  const rows = [{outcome:'HTTP_RESPONSE',elapsedMs:80},{outcome:'REQUEST_TIMEOUT',elapsedMs:50},{outcome:'NETWORK_ERROR',elapsedMs:200}]
  assert.deepEqual(timeoutComparison(rows,100),{n:3,exceeds:0,within:1,unknown:2,httpN:1,httpExceeds:0,httpRatio:0})
  assert.equal(timeoutComparison(rows,40).exceeds,2)
})
test('retry model counts additional attempts and filters observed hints by selected status', () => {
  const p={retries:2,initialMs:100,capMs:500,attemptMs:1000,deadlineMs:8000,respectRetryAfter:true,statuses:[503]}
  const rows=[{httpCode:503,retryAfter:{headerMs:1000}},{httpCode:400,retryAfter:{headerMs:90000}}]
  const result=retryComparison(p,rows)
  assert.equal(result[0].scenarioMs,5000);assert.equal(result[0].observedHintN,1);assert.equal(result[0].eligibleN,1)
  assert.equal(result[1].attempts,3)
  assert.equal(retryComparison({...p,retries:1.5},rows).length,0)
})
test('polling counts only timestamped non-immediate workflows and marks deadline misses', () => {
  const result=pollingComparison([{checkRef:'a',businessDurationMs:2100,postInitialStatus:'pending'},
    {checkRef:'b',businessDurationMs:0,postInitialStatus:'clear'},{checkRef:'c',state:'CENSORED'}],1000,2,3000)
  assert.equal(result.n,1);assert.equal(result.excluded,2);assert.equal(result.rows[0].calls,3)
  assert.equal(result.rows[0].delayMs,900);assert.equal(result.rows[0].found,false);assert.equal(result.delay.n,0)
})
test('draft exports retain TODO and identify local mock evidence', () => {
  const result={run:{runId:'run-test',source:'LOCAL_MOCK',state:'COMPLETED',conditions:{}},attempts:[],phases:[],workflows:[],postComparison:{}}
  const candidates={timeoutMs:100,retry:{},retryStatuses:'',pollIntervalMs:1000,pollMax:2,pollDurationMs:3000}
  assert.match(configuration([]),/TODO/)
  assert.match(markdown(result,[],candidates),/외부 실측으로 제출 금지/)
  assert.match(markdown(result,[],candidates),/추가 실험 필요/)
  assert.match(csv(result),/source=LOCAL_MOCK/)
})
test('Markdown candidate statistics use the selected endpoint without mixing POST latency', () => {
  const result={run:{runId:'run-test',source:'LOCAL_MOCK',state:'COMPLETED',conditions:{}},
    attempts:[{sequence:1,endpoint:'CREATE',outcome:'HTTP_RESPONSE',httpCode:201,elapsedMs:200},
      {sequence:2,endpoint:'DETAIL',outcome:'HTTP_RESPONSE',httpCode:200,elapsedMs:10}],phases:[],workflows:[],postComparison:{}}
  const candidates={timeoutMs:50,retry:{},retryStatuses:'',pollIntervalMs:1000,pollMax:2,pollDurationMs:3000}
  const report=markdown(result,[],candidates,'DETAIL')
  assert.match(report,/HTTP 통계 대상: DETAIL; 대상 시도 n=1/)
  assert.match(report,/완료 HTTP 초과 0\/1/)
  assert.match(report,/명세 대조 \(실행 전체 n=2\)/)
  assert.match(report,/CREATE \| 1 \| 200\.00/)
})

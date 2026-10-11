<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import LabCampaignPanel from './LabCampaignPanel.vue'
import { streamExperiment } from '../lab/stream'
import { api, errorMessage, postWithCsrf } from '../api/portal'
import { statistics, summarize, timeoutComparison, retryComparison, pollingComparison, display } from '../lab/analysis'
import { configuration, csv, markdown, download } from '../lab/exports'

const props = defineProps({ employees: { type: Array, default: () => [] } })
defineEmits(['close'])
const caps = ref(null), busy = ref(false), error = ref(''), result = ref(null), events = ref(0), cohort = ref('ALL')
const campaignBusy = ref(false)
let abort
const form = reactive({ candidateKey: '', type: 'GET_BASELINE', endpoint: 'DETAIL', employeeId: '', checkId: '',
  firstName: '', lastName: '', dateOfBirth: '', repeats: 5, concurrency: 1,
  connectTimeoutMs: 5000, requestTimeoutMs: 10000, durationMs: 120000, intervalMs: 1000,
  pollIntervalMs: 5000, maxPolls: 5, callBudget: 20, postAcknowledged: false })
const candidates = reactive({ timeoutMs: null, retry: { retries: 0, initialMs: null, capMs: null, attemptMs: null, deadlineMs: null, respectRetryAfter: true },
  retryStatuses: '', pollIntervalMs: null, pollMax: null, pollDurationMs: null })
const draft = reactive({ setting: 'request-timeout-ms', value: '', reason: '', alternative: '', failureCase: '' })
const decisions = ref([])
const showJson = ref(false)
const exportJson = computed(() => result.value ? JSON.stringify({ ...result.value, decisions: decisions.value, candidates }, null, 2) : '')
const isPost = computed(() => ['CREATE_POLL','REPEAT_POST'].includes(form.type))
const planned = computed(() => form.type === 'GET_CONCURRENCY' ? form.repeats * form.concurrency
  : form.type === 'CREATE_POLL' ? 1 + form.maxPolls : form.type === 'REPEAT_POST' ? 4 : form.repeats)
const allAttempts = computed(() => result.value?.attempts || [])
const filtered = computed(() => allAttempts.value.filter(a => cohort.value === 'ALL' || a.endpoint === cohort.value))
const summary = computed(() => summarize(filtered.value, result.value?.phases || []))
const endpointStats = computed(() => [...new Set(allAttempts.value.map(a => a.endpoint))].map(endpoint => ({
  endpoint, ...statistics(allAttempts.value.filter(a => a.endpoint === endpoint && a.outcome === 'HTTP_RESPONSE').map(a => a.elapsedMs)) })))
const timeout = computed(() => timeoutComparison(filtered.value, candidates.timeoutMs))
const retry = computed(() => retryComparison({ ...candidates.retry, statuses: candidates.retryStatuses.split(',').map(s => Number(s.trim())).filter(n => n >= 100 && n <= 599) }, filtered.value))
const polling = computed(() => pollingComparison(result.value?.workflows || [], candidates.pollIntervalMs, candidates.pollMax, candidates.pollDurationMs))
const business = computed(() => statistics((result.value?.workflows || []).map(w => w.businessDurationMs).filter(v => Number.isFinite(v) && v >= 0)))
const latencyPlot = computed(() => {
  const rows = filtered.value.filter(a => Number.isFinite(a.elapsedMs)).slice().sort((a,b) => Date.parse(a.startedAt)-Date.parse(b.startedAt))
  const max = Math.max(1, ...rows.map(a => a.elapsedMs))
  return { max, points: rows.map((a,i) => ({ ...a, x: rows.length === 1 ? 350 : 55+i*620/(rows.length-1), y: 200-a.elapsedMs/max*165 })),
    p95Y: summary.value.http.n ? 200-summary.value.http.p95/max*165 : null }
})
const findings = computed(() => allAttempts.value.map(a => ({ sequence: a.sequence, n: 1,
  result: a.outcome !== 'HTTP_RESPONSE' ? '응답 관찰 불가' : a.issues?.length ? a.issues.join(', ') : '검사 항목 차이 미관측' })))
const settings = ['connect-timeout-ms','request-timeout-ms','retry-statuses','max-retries','retry-backoff','retry-initial-delay-ms',
  'retry-max-delay-ms','respect-retry-after','retry-max-duration-ms','polling-interval-ms','polling-max-attempts','polling-max-duration-ms','max-in-flight']
const valid = computed(() => !campaignBusy.value && caps.value?.enabled && form.candidateKey.trim() && planned.value <= form.callBudget &&
  (!isPost.value || caps.value.postEnabled && form.postAcknowledged && form.firstName && form.lastName && form.dateOfBirth) &&
  (form.endpoint === 'DETAIL' ? form.checkId : form.employeeId))
onMounted(async () => { try { caps.value = (await api.get('/admin/reliability-lab/capabilities')).data } catch (e) { error.value = errorMessage(e) } })
onUnmounted(() => { abort?.abort(); form.candidateKey = '' })
function changeType() {
  form.endpoint = isPost.value ? 'CREATE' : 'DETAIL'
  form.concurrency = 1; form.postAcknowledged = false
}
function chooseEmployee() {
  const employee = props.employees.find(e => e.employeeId === form.employeeId)
  form.dateOfBirth = employee?.dateOfBirth || ''
  form.firstName = ''; form.lastName = ''
}
function acceptEvent(event) {
  events.value++
  if (event.type === 'start') result.value = { run: event.data.run, attempts: [], phases: [], workflows: [], postComparison: {} }
  if (event.type === 'progress') {
    result.value.run = event.data.run
    result.value.attempts.push(event.data.attempt)
  }
  if (event.type === 'result') result.value = event.data
}
async function execute() {
  if (campaignBusy.value) return
  error.value = ''; busy.value = true; result.value = null; decisions.value = []; events.value = 0; showJson.value = false
  cohort.value = isPost.value && form.type === 'CREATE_POLL' ? 'DETAIL' : form.endpoint
  abort = new AbortController()
  try {
    const body = { ...form, candidateKey: form.candidateKey.trim() }
    form.candidateKey = ''
    try { await streamExperiment(body, acceptEvent, abort.signal) } finally { body.candidateKey = '' }
  } catch (e) {
    error.value = e.name === 'AbortError' ? '전송을 중단했습니다. 이미 전송된 POST는 취소되지 않을 수 있습니다.' : (e.message || errorMessage(e))
    if (result.value) { result.value.run.state = 'INTERRUPTED'; result.value.run.reason ||= 'STREAM_INTERRUPTED' }
  } finally { form.candidateKey = ''; busy.value = false }
}
async function cancel() {
  try {
    if (result.value?.run?.runId) await postWithCsrf('/admin/reliability-lab/experiments/' + result.value.run.runId + '/cancel')
    else abort?.abort()
  } catch (e) { error.value = errorMessage(e) }
}
function addDecision() {
  error.value = ''
  if (!result.value || !draft.value.trim() || !draft.reason.trim() || !draft.alternative.trim() || !draft.failureCase.trim()) {
    error.value = '선택값과 판단 근거 세 항목을 모두 입력하세요.'; return
  }
  let value
  if (draft.setting === 'retry-statuses') {
    value = draft.value.split(',').map(v => Number(v.trim()))
    if (value.some(v => !Number.isInteger(v) || v < 100 || v > 599)) { error.value = 'HTTP 코드 목록을 확인하세요.'; return }
  } else if (draft.setting === 'retry-backoff') {
    value = draft.value.toUpperCase()
    if (!['FIXED','EXPONENTIAL'].includes(value)) { error.value = 'FIXED 또는 EXPONENTIAL을 입력하세요.'; return }
  } else if (draft.setting === 'respect-retry-after') {
    if (!['true','false'].includes(draft.value)) { error.value = 'true 또는 false를 입력하세요.'; return }
    value = draft.value === 'true'
  } else {
    value = Number(draft.value)
    if (!Number.isInteger(value) || value < 0 || value > 86400000 || value === 0 && !['max-retries','retry-initial-delay-ms'].includes(draft.setting)) {
      error.value = '설정값은 범위 안의 정수여야 합니다.'; return
    }
  }
  decisions.value = decisions.value.filter(d => d.setting !== draft.setting)
  decisions.value.push({ setting: draft.setting, value, runId: result.value.run.runId, source: result.value.run.source,
    status: 'DRAFT', cohort: cohort.value, sampleCounts: { attempts: summary.value.n, http: summary.value.http.n, completedWorkflows: business.value.n },
    statistics: { ...summary.value.http }, candidates: JSON.parse(JSON.stringify(candidates)),
    reason: draft.reason.trim(), alternative: draft.alternative.trim(), failureCase: draft.failureCase.trim() })
}
function save(kind) {
  const filename = 'lab-' + result.value.run.runId
  if (kind === 'json') download(filename + '.json', exportJson.value, 'application/json')
  if (kind === 'csv') download(filename + '.csv', csv(result.value), 'text/csv')
  if (kind === 'md') download(filename + '.md', markdown(result.value, decisions.value, candidates, cohort.value), 'text/markdown')
  if (kind === 'yaml') download(filename + '-draft.yaml', configuration(decisions.value), 'application/yaml')
}
</script>

<template>
  <section class="lab-page">
    <div class="portal-section-title"><div><h2>API Reliability Lab</h2><p>실측 → 분석 → 후보 비교 → 개발자 판단 → 설정 초안</p></div>
      <Button label="직원 목록으로" severity="secondary" :disabled="busy || campaignBusy" @click="$emit('close')" /></div>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="caps?.source === 'LOCAL_MOCK'" severity="warn" :closable="false">로컬 모의 API 검증 모드입니다. 아래 결과는 외부 API 실측이 아닙니다.</Message>
    <Message v-if="caps && !caps.enabled" severity="info" :closable="false">실제 호출은 비활성입니다. 승인된 시험 조건으로 서버를 활성화한 뒤 실행하세요.</Message>
    <LabCampaignPanel :caps="caps" :blocked="busy" @busy="campaignBusy=$event" @result="result=$event; decisions=[]; cohort='ALL'" />
    <section class="portal-panel">
      <h3>A. 실험 설정</h3>
      <p>입력값은 측정 조건입니다. 운영 추천값이 아닙니다. 키는 전송 후 지우며 저장하거나 내보내지 않습니다.</p>
      <form class="lab-grid" @submit.prevent="execute">
        <div class="lab-field"><label for="lab-key">X-Candidate-Key</label><InputText id="lab-key" v-model="form.candidateKey" type="password" autocomplete="off" maxlength="256" :disabled="busy" /></div>
        <div class="lab-field"><label for="lab-type">실험 유형</label><select id="lab-type" v-model="form.type" :disabled="busy" @change="changeType">
          <option value="GET_BASELINE">GET 순차</option><option value="GET_CONCURRENCY">GET 동시성 비교</option>
          <option value="CREATE_POLL">POST 1회 + 완료 관찰</option><option value="REPEAT_POST">동일 employeeId 반복 POST</option></select></div>
        <div class="lab-field"><label for="lab-endpoint">API Endpoint</label><select id="lab-endpoint" v-model="form.endpoint" :disabled="busy || isPost">
          <option value="DETAIL">GET /background-checks/{checkId}</option><option value="LIST">GET /background-checks?employeeId</option><option v-if="isPost" value="CREATE">POST /background-checks</option></select></div>
        <div class="lab-field"><label for="lab-employee">employeeId</label><InputText id="lab-employee" v-model="form.employeeId" list="lab-employees" :disabled="busy" @change="chooseEmployee" />
          <datalist id="lab-employees"><option v-for="e in employees" :key="e.employeeId" :value="e.employeeId">{{ e.fullName }}</option></datalist></div>
        <div v-if="form.endpoint === 'DETAIL'" class="lab-field"><label for="lab-check">checkId</label><InputText id="lab-check" v-model="form.checkId" :disabled="busy" maxlength="128" /></div>
        <template v-if="isPost">
          <div class="lab-field"><label for="lab-last">조회용 성</label><InputText id="lab-last" v-model="form.lastName" :disabled="busy" /></div>
          <div class="lab-field"><label for="lab-first">조회용 이름</label><InputText id="lab-first" v-model="form.firstName" :disabled="busy" /></div>
          <div class="lab-field"><label for="lab-birth">조회용 생년월일</label><InputText id="lab-birth" v-model="form.dateOfBirth" type="date" :disabled="busy" /></div>
        </template>
        <div class="lab-field"><label for="lab-repeats">단계별 반복 횟수</label><InputNumber inputId="lab-repeats" v-model="form.repeats" :min="1" :max="20" :disabled="busy || isPost" /></div>
        <div class="lab-field"><label for="lab-concurrency">최대 비교 동시성</label><InputNumber inputId="lab-concurrency" v-model="form.concurrency" :min="1" :max="3" :disabled="busy || form.type !== 'GET_CONCURRENCY'" /></div>
        <div class="lab-field"><label for="lab-connect">측정용 연결 제한 ms</label><InputNumber inputId="lab-connect" v-model="form.connectTimeoutMs" :min="1" :max="30000" :disabled="busy" /></div>
        <div class="lab-field"><label for="lab-request">측정용 전체 요청 제한 ms</label><InputNumber inputId="lab-request" v-model="form.requestTimeoutMs" :min="1" :max="60000" :disabled="busy" /></div>
        <div class="lab-field"><label for="lab-duration">실험 최대 기간 ms</label><InputNumber inputId="lab-duration" v-model="form.durationMs" :min="1" :max="120000" :disabled="busy" /></div>
        <div class="lab-field"><label for="lab-interval">요청 묶음 간 대기 ms</label><InputNumber inputId="lab-interval" v-model="form.intervalMs" :min="0" :max="10000" :disabled="busy" /></div>
        <div class="lab-field"><label for="lab-budget">전체 외부 호출 예산</label><InputNumber inputId="lab-budget" v-model="form.callBudget" :min="1" :max="20" :disabled="busy" /></div>
        <template v-if="form.type === 'CREATE_POLL'">
          <div class="lab-field"><label for="lab-poll">측정용 폴링 간격 ms</label><InputNumber inputId="lab-poll" v-model="form.pollIntervalMs" :min="1" :disabled="busy" /></div>
          <div class="lab-field"><label for="lab-poll-max">최대 추가 GET 수</label><InputNumber inputId="lab-poll-max" v-model="form.maxPolls" :min="1" :max="19" :disabled="busy" /></div>
        </template>
        <div v-if="isPost" class="lab-wide"><label><input v-model="form.postAcknowledged" type="checkbox" :disabled="busy" /> 승인받은 시험용 데이터와 POST 계획입니다.</label>
          <p>외부 기록이 생성되며 삭제 API가 없습니다. 타임아웃 시 결과 불명으로 처리하고 재전송하지 않습니다.</p></div>
        <div class="lab-wide portal-actions"><Button label="실험 실행" type="submit" :disabled="!valid || busy" :loading="busy" />
          <Button label="실험 중단" severity="danger" :disabled="!busy" @click="cancel" />
          <span>예정 최대 {{ planned }}회 / 예산 {{ form.callBudget }}회 · 서버 동시 호출 ≤3</span></div>
      </form>
      <p v-if="result">실행 {{ result.run.runId }} · {{ busy ? '진행 중' : result.run.state }} · 관측 {{ allAttempts.length }} / 예정 상한 {{ result.run.plannedUpperBound }} · 이벤트 {{ events }} · 중단 {{ result.run.reason || '없음' }}</p>
      <p>실험은 1개씩 실행합니다. 서버 상한은 단일 프로세스 기준이며 다중 인스턴스 합산 제한은 별도 검증이 필요합니다.</p>
    </section>
    <section class="portal-panel">
      <h3>B. 실측 데이터 분석</h3>
      <p>측정 출처: {{ result?.run?.source || caps?.source || '미확인' }}<span v-if="(result?.run?.source || caps?.source) === 'LOCAL_MOCK'"> · 로컬 모의 검증이며 외부 실측이 아닙니다.</span></p>
      <div class="lab-field"><label for="lab-cohort">HTTP 통계 대상</label><select id="lab-cohort" v-model="cohort"><option value="ALL">전체 (Endpoint 혼합 가능)</option><option value="DETAIL">상세 GET</option><option value="LIST">이력 GET</option><option value="CREATE">POST</option></select></div>
      <div class="lab-metrics">
        <div v-for="item in [['전체 시도',summary.n,summary.n],['완료 HTTP n',summary.http.n,summary.n],['평균 ms',display(summary.http.mean),summary.http.n],['중앙값/p50 ms',display(summary.http.p50),summary.http.n],['p95 ms',display(summary.http.p95),summary.http.n],['p99 ms',display(summary.http.p99),summary.http.n],['최대 ms',display(summary.http.max),summary.http.n],['평균−중앙값 ms',display(summary.http.meanMedianGap),summary.http.n]]" :key="item[0]"><span>{{ item[0] }}</span><strong>{{ item[1] }}</strong><small>집계 표본 n={{ item[2] }}</small></div>
      </div>
      <p>Type 7 선형 보간: h=(n−1)p. 완료 HTTP 응답(4xx/5xx 포함)의 본문 수신까지 계측하며 JSON 해석·Lab 큐 대기는 제외합니다.</p>
      <Message severity="info" :closable="false">p99는 상위 소수 관측에 민감합니다. n만으로 무효라고 단정하지 않지만 장기 SLO를 보장하지 않습니다.
        <span v-if="summary.http.n">현재 p99는 {{ summary.http.tailRanks.join('·') }}번째 표본을 보간합니다.</span>
        타임아웃된 느린 요청이 제외되어 완료 응답 통계가 낙관적으로 보일 수 있습니다.</Message>
      <p>참고 이상치(Q3+1.5×IQR 초과): {{ summary.http.outliers.length }} / n={{ summary.http.n }}. 제거하지 않음. 전송 중단 경과 평균 {{ display(summary.abort.mean) }}ms / n={{ summary.abort.n }}.</p>
      <figure v-if="latencyPlot.points.length" class="lab-latency-plot">
        <figcaption>요청 시작 시각 순 경과시간 · n={{ latencyPlot.points.length }} (완료 HTTP n={{ summary.http.n }}, 전송 중단 n={{ summary.abort.n }})</figcaption>
        <svg viewBox="0 0 710 240" role="img" aria-label="요청 시작 시각 순 경과시간 산점도">
          <line x1="55" y1="35" x2="55" y2="200" stroke="#94a3b8" /><line x1="55" y1="200" x2="680" y2="200" stroke="#94a3b8" />
          <text x="4" y="30" fill="#475569" font-size="12">{{ display(latencyPlot.max) }}ms</text><text x="25" y="204" fill="#475569" font-size="12">0</text>
          <line v-if="latencyPlot.p95Y !== null" x1="55" x2="680" :y1="latencyPlot.p95Y" :y2="latencyPlot.p95Y" stroke="#64748b" stroke-dasharray="5 5" />
          <circle v-for="point in latencyPlot.points" :key="point.attemptId" :cx="point.x" :cy="point.y" r="5" :fill="point.outcome === 'HTTP_RESPONSE' ? '#2563eb' : '#d97706'">
            <title>요청 {{ point.sequence }} · {{ point.outcome }} · {{ display(point.elapsedMs) }}ms</title>
          </circle>
          <text x="250" y="228" fill="#475569" font-size="12">요청 시작 순서 (동시 요청은 거의 같은 시각)</text>
        </svg>
        <p>파랑: 완료 HTTP · 주황: 중단 시 경과시간(전체 응답시간 미확정) · 점선: 완료 HTTP p95. Endpoint 혼합과 초기 연결 지연을 함께 확인하세요.</p>
      </figure>
      <div class="lab-grid"><div><h4>HTTP 상태코드</h4><DataTable :value="summary.statuses"><Column field="value" header="코드" /><Column field="count" header="건수" /><Column field="n" header="분모 n" /><Column header="비율 %"><template #body="{data}">{{ display(data.ratio) }}</template></Column></DataTable></div>
        <div><h4>전송 결과</h4><DataTable :value="summary.outcomes"><Column field="value" header="결과" /><Column field="count" header="건수" /><Column field="n" header="분모 n" /></DataTable></div></div>
      <p>HTTP 코드와 전송 오류는 다른 축입니다. 헤더 수신 후 본문 타임아웃이면 둘 다 관측될 수 있으며 이중으로 합산하지 않습니다.</p>
      <h4>Endpoint별 지연</h4><DataTable :value="endpointStats"><Column field="endpoint" header="Endpoint" /><Column field="n" header="n_http" /><Column header="p95 ms"><template #body="{data}">{{ display(data.p95) }}</template></Column><Column header="최대 ms"><template #body="{data}">{{ display(data.max) }}</template></Column></DataTable>
      <h4>동시성 비교</h4><DataTable :value="summary.concurrency"><Column field="concurrency" header="동시성" /><Column field="n" header="전체 n" /><Column field="latencyN" header="지연 n" /><Column header="p95 ms"><template #body="{data}">{{ display(data.p95) }}</template></Column><Column field="failed" header="오류 수" /><Column header="오류율 %"><template #body="{data}">{{ display(data.errorRatio) }}</template></Column><Column header="완료/s"><template #body="{data}">{{ display(data.throughput) }}</template></Column></DataTable>
      <p>동일 실행 조건에서 비교합니다. 단계 순서·시간 변화의 영향은 분리되지 않았습니다. 오류 0건이나 동시성 3이 안전한 운영 상한을 보장하지 않습니다.</p>
      <h4>HTTP 요청별 관측</h4><DataTable :value="allAttempts" scrollable :tableStyle="{minWidth:'65rem'}"><Column field="sequence" header="#" /><Column field="endpoint" header="Endpoint" /><Column field="concurrency" header="동시성" /><Column field="httpCode" header="HTTP" /><Column field="outcome" header="전송 결과" /><Column header="경과 ms"><template #body="{data}">{{ display(data.elapsedMs) }}</template></Column><Column header="큐 ms"><template #body="{data}">{{ display(data.queueWaitMs) }}</template></Column><Column field="status" header="업무 상태" /><Column header="Retry-After ms"><template #body="{data}">{{ data.retryAfter?.headerMs ?? data.retryAfter?.bodyMs ?? '미관측' }}</template></Column></DataTable>
      <h4>배경 조회 업무 처리시간</h4><p>업무 평균 {{ display(business.mean) }}ms / 완료 시각 표본 n={{ business.n }} · 관측 업무 전체 n={{ result?.workflows?.length || 0 }}. 완료 표본만의 통계이며 미완료 건은 별도입니다.</p>
      <DataTable :value="result?.workflows || []"><Column field="checkRef" header="조회 별칭" /><Column field="state" header="관측 종료" /><Column field="businessDurationMs" header="서버 업무 ms" /><Column field="observedPendingToFinalMs" header="pending→발견 ms" /><Column field="finalStatus" header="최종 상태" /></DataTable>
      <p>서버 createdAt/completedAt이 없으면 정확한 업무 완료시간은 추가 실험 필요입니다. 발견 시각으로 대체하지 않습니다.</p>
      <h4>반복 POST</h4><pre>{{ result?.postComparison && Object.keys(result.postComparison).length ? JSON.stringify(result.postComparison,null,2) : '추가 실험 필요' }}</pre>
      <h4>명세 대조</h4><DataTable :value="findings"><Column field="sequence" header="요청" /><Column field="n" header="n" /><Column field="result" header="대조 결과" /></DataTable>
      <p>코드·JSON·필드·enum·ID·시각만 검사합니다. EXPECTED_*는 앱 필요 조건이며 Swagger의 required 선언과 구분합니다. 관찰하지 않은 명세 동작은 TODO입니다.</p>
    </section>
    <section class="portal-panel">
      <h3>C. 설계값 시뮬레이터</h3>
      <p>후보값을 바꾸어도 API를 호출하지 않습니다. 아래 결과는 관측 재분류 또는 명시된 가정의 수학 모델입니다. 전송 중단 경과시간에는 타이머·취소 지연이 포함되어 후보 초과를 확정하지 않고 미확정으로 표시합니다.</p>
      <h4>Timeout 후보</h4><div class="lab-field"><label for="candidate-timeout">후보 전체 요청 제한 ms</label><InputNumber inputId="candidate-timeout" v-model="candidates.timeoutMs" :min="1" /></div>
      <p>완료 HTTP 초과 {{ timeout.httpExceeds }}/{{ timeout.httpN }} ({{ display(timeout.httpRatio) }}%) · 전체 n={{ timeout.n }}에서 확정 초과 {{ timeout.exceeds }}, 비초과 {{ timeout.within }}, 미확정 {{ timeout.unknown }}.</p>
      <p>p95={{ display(summary.http.p95) }}ms, p99={{ display(summary.http.p99) }}ms / n={{ summary.http.n }}. 짧으면 조기 중단·재호출 부담, 길면 리소스 점유·대기가 증가합니다. 연결 단계 시간은 계측하지 않아 connect-timeout 판단은 추가 실험 필요입니다.</p>
      <h4>Retry 후보 — 성공률은 추가 실험 필요</h4><div class="lab-grid">
        <div class="lab-field"><label for="retry-codes">대상 HTTP 코드 (쉼표 구분)</label><InputText id="retry-codes" v-model="candidates.retryStatuses" placeholder="직접 선택" /></div>
        <div class="lab-field"><label for="retry-count">최대 추가 시도 수 R</label><InputNumber inputId="retry-count" v-model="candidates.retry.retries" :min="0" :max="10" /></div>
        <div class="lab-field"><label for="retry-initial">초기 대기 ms</label><InputNumber inputId="retry-initial" v-model="candidates.retry.initialMs" :min="0" /></div>
        <div class="lab-field"><label for="retry-cap">Backoff 상한 ms</label><InputNumber inputId="retry-cap" v-model="candidates.retry.capMs" :min="0" /></div>
        <div class="lab-field"><label for="retry-attempt">가정한 1회 전체 상한 ms</label><InputNumber inputId="retry-attempt" v-model="candidates.retry.attemptMs" :min="1" /></div>
        <div class="lab-field"><label for="retry-deadline">전체 재시도 마감 ms</label><InputNumber inputId="retry-deadline" v-model="candidates.retry.deadlineMs" :min="1" /></div>
      </div><label><input v-model="candidates.retry.respectRetryAfter" type="checkbox" /> Retry-After 존중 후보</label>
      <DataTable :value="retry"><Column field="mode" header="Backoff" /><Column field="attempts" header="총 시도" /><Column field="scenarioMs" header="시나리오 대기 ms" /><Column field="deadlineMs" header="전체 deadline ms" /><Column field="sampleN" header="관측 n" /><Column field="eligibleN" header="대상 코드 n" /><Column field="observedHintN" header="Retry-After n" /></DataTable>
      <p>W=(R+1)×1회 전체 상한+대기 합. 연결/read 제한만으로 전체 상한을 가정하지 않습니다. Retry-After는 헤더 우선·body fallback이며 불일치도 원본에 기록됩니다. 미래 안내 대기시간은 알 수 없으며 deadline을 넘으면 중단합니다. POST에 GET 재시도를 그대로 적용하지 않습니다.</p>
      <h4>Polling 후보</h4><div class="lab-grid">
        <div class="lab-field"><label for="candidate-poll">주기 ms</label><InputNumber inputId="candidate-poll" v-model="candidates.pollIntervalMs" :min="1" /></div>
        <div class="lab-field"><label for="candidate-poll-max">최대 추가 GET 수</label><InputNumber inputId="candidate-poll-max" v-model="candidates.pollMax" :min="1" /></div>
        <div class="lab-field"><label for="candidate-poll-duration">최대 대기 ms</label><InputNumber inputId="candidate-poll-duration" v-model="candidates.pollDurationMs" :min="1" /></div>
      </div><DataTable :value="polling.rows"><Column field="checkRef" header="별칭" /><Column field="businessDurationMs" header="업무 d ms" /><Column field="calls" header="모델 GET 수" /><Column field="delayMs" header="모델 발견 지연 ms" /><Column field="found" header="후보 예산 내 발견" /></DataTable>
      <p>모델 n={{ polling.n }}, 제외={{ polling.excluded }}, 발견 지연 평균 {{ display(polling.delay.mean) }}ms / 발견 n={{ polling.delay.n }}. ceil(d/주기) 모델은 POST 수신 지연·HTTP 지연·실패·Retry-After를 생략합니다. 즉시 완료 POST는 제외합니다.</p>
      <h4>동시성 후보</h4><p>B의 같은 조건 비교를 근거로 아래 판단 기록에서 max-in-flight를 선택하세요. 관측하지 않은 동시성의 응답시간·안전성은 추가 실험 필요입니다.</p>
    </section>
    <section class="portal-panel">
      <h3>D. 개발자 판단 기록</h3><p>DRAFT입니다. 사용자 확인 전 운영 적용하지 않습니다. 이유에 키·개인정보를 적지 마세요.</p>
      <form class="lab-grid" @submit.prevent="addDecision">
        <div class="lab-field"><label for="decision-setting">설정 항목</label><select id="decision-setting" v-model="draft.setting"><option v-for="s in settings" :key="s">{{ s }}</option></select></div>
        <div class="lab-field"><label for="decision-value">선택값 (시간은 ms)</label><InputText id="decision-value" v-model="draft.value" /></div>
        <div class="lab-field"><label for="decision-reason">선택 이유</label><InputText id="decision-reason" v-model="draft.reason" /></div>
        <div class="lab-field"><label for="decision-alternative">반대 선택의 장점</label><InputText id="decision-alternative" v-model="draft.alternative" /></div>
        <div class="lab-field"><label for="decision-failure">부적절해질 상황</label><InputText id="decision-failure" v-model="draft.failureCase" /></div>
        <div class="lab-wide"><Button label="판단 초안 기록" type="submit" :disabled="!result || busy" /></div>
      </form><DataTable :value="decisions"><Column field="setting" header="설정" /><Column field="value" header="선택값" /><Column field="cohort" header="관측 대상" /><Column field="status" header="상태" /><Column field="reason" header="이유" /></DataTable>
    </section>
    <section class="portal-panel">
      <h3>E. 설정·산출물 초안</h3><div class="portal-actions">
        <Button label="원본 JSON" :disabled="!result || busy" @click="save('json')" /><Button label="원본 CSV" :disabled="!result || busy" @click="save('csv')" />
        <Button label="JSON 텍스트 보기" severity="secondary" :disabled="!result || busy" @click="showJson = !showJson" />
        <Button label="MEASUREMENTS Markdown" :disabled="!result || busy" @click="save('md')" /><Button label="설정 YAML 초안" :disabled="!result || busy" @click="save('yaml')" /></div>
      <div v-if="showJson"><label for="lab-export-json">다운로드가 열리지 않으면 아래 원본을 선택해 복사하고 .json 파일로 저장하세요.</label>
        <textarea id="lab-export-json" :value="exportJson" readonly rows="12" style="width:100%;margin-top:0.5rem" />
        <p>{{ caps?.recordingEnabled ? '측정 메타데이터는 서버에 자동 저장됩니다. 판단 초안은 별도로 내보내세요.' : '서버 자동 저장이 비활성입니다. 새 실험이나 새로고침 전에 저장하세요.' }}</p></div>
      <pre>{{ configuration(decisions) }}</pre><p>미선택 항목은 TODO입니다. 현재 코드의 설정 바인딩은 확인 후 연결합니다. 문서·운영 설정을 자동으로 덮어쓰지 않습니다.</p>
    </section>
  </section>
</template>

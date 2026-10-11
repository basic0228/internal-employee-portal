<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { api, postWithCsrf, errorMessage } from '../api/portal'
import { defaultCases, nextRequest, caseProgress, canContinue, caseLabel } from '../lab/campaign'
import { streamExperiment } from '../lab/stream'
import { download } from '../lab/exports'
import { followupPlans, followupCases, isPostType, comparisonRows } from '../lab/followup'
import { display } from '../lab/analysis'
const props = defineProps({ caps: Object, blocked: Boolean })
const emit = defineEmits(['busy', 'result'])
const plans = ref([]), selectedPlan = ref(''), selectedCase = ref(''), saved = ref(null), records = ref([])
const busy = ref(false), error = ref(''), notice = ref(''), approved = ref(false), text = ref(false)
const planKind = ref('B01')
const inputs = reactive({ candidateKey: '', employeeId: '', checkId: '', firstName: '', lastName: '', dateOfBirth: '' })
let abort, stopped = false, runId
const cases = computed(() => (saved.value?.plan.cases || []).map(c => ({ ...c, ...caseProgress(c, saved.value.runs), label: caseLabel(c.conditions) })))
const chosen = computed(() => cases.value.find(c => c.caseId === selectedCase.value))
const bundle = computed(() => saved.value ? JSON.stringify(saved.value, null, 2) : '')
const post = computed(() => isPostType(chosen.value?.conditions.type))
const comparisons = computed(() => comparisonRows(saved.value?.runs || []))
const plannedCases = computed(() => planKind.value === 'ORIGINAL' ? defaultCases() : followupCases(planKind.value))
const planBudget = computed(() => plannedCases.value.reduce((n,c) => n+c.goal,0))
const ready = computed(() => props.caps?.enabled && props.caps?.recordingEnabled && chosen.value?.remaining > 0 && !chosen.value.unresolved &&
  !chosen.value.procedureDone && !busy.value && !props.blocked && approved.value && inputs.candidateKey.trim() &&
  (chosen.value.conditions.endpoint === 'DETAIL' ? inputs.checkId : inputs.employeeId) &&
  (!post.value || props.caps.postEnabled && inputs.firstName && inputs.lastName && inputs.dateOfBirth))
async function refresh() {
  if (!props.caps?.recordingEnabled) return
  try {
    plans.value = (await api.get('/admin/reliability-lab/campaigns')).data
    records.value = (await api.get('/admin/reliability-lab/recordings')).data
    if (selectedPlan.value) await loadPlan()
  } catch (e) { error.value = errorMessage(e) }
}
async function loadPlan() {
  saved.value = (await api.get('/admin/reliability-lab/campaigns/' + selectedPlan.value)).data
  if (!saved.value.plan.cases.some(c => c.caseId === selectedCase.value)) selectedCase.value = saved.value.plan.cases[0]?.caseId || ''
}
async function create() {
  error.value = ''
  try {
    const { data } = await postWithCsrf('/admin/reliability-lab/campaigns', { cases: plannedCases.value })
    selectedPlan.value = data.campaignId; selectedCase.value = ''; await refresh()
    notice.value = '계획만 저장했습니다. 외부 호출은 실행 버튼을 누를 때 시작합니다.'
  } catch (e) { error.value = errorMessage(e) }
}
watch(() => props.caps?.recordingEnabled, refresh)
watch(selectedCase, () => {
  approved.value = false; inputs.candidateKey = ''
  // Separate fake targets for polling and duplicate experiments. GET conditions share one fake target.
  const kind = chosen.value?.conditions.type === 'CREATE_POLL' ? 'POLL' : chosen.value?.conditions.type === 'REPEAT_POST' ? 'DUP' : 'GET'
  inputs.employeeId = selectedPlan.value ? `LAB-${selectedPlan.value.slice(0, 8)}-${kind}` : ''
})
onMounted(refresh)
onUnmounted(() => { stopped = true; abort?.abort(); inputs.candidateKey = '' })
async function execute() {
  if (!ready.value) return
  error.value = ''; notice.value = ''; busy.value = true; emit('busy', true); stopped = false
  abort = new AbortController()
  const key = inputs.candidateKey.trim(); inputs.candidateKey = ''
  const targets = { ...inputs, candidateKey: key }
  try {
    while (!stopped) {
      await loadPlan()
      const entry = chosen.value
      if (!entry || entry.remaining === 0 || entry.unresolved || entry.procedureDone) break
      const request = nextRequest(entry.conditions, entry.remaining, targets)
      const result = await streamExperiment(request, event => {
        if (event.data.run?.runId) runId = event.data.run.runId
        if (event.type === 'result') { runId = null; emit('result', event.data) }
        if (event.type === 'progress') notice.value = `현재 차수 관측 ${event.data.run.finished}건 · 서버에 자동 저장 중`
      }, abort.signal, selectedPlan.value, entry.caseId)
      await loadPlan()
      if (entry.conditions.protocol) {
        notice.value = `탐색 절차 종료: ${result.run.state} / ${result.run.reason || '정상'}. 조건부 후속 호출을 생략한 예산은 미사용으로 남깁니다. 다음 케이스는 검토 후 직접 선택하세요.`
        break
      }
      if (!canContinue(result)) { notice.value = '오류·중단·결과 불명으로 케이스를 멈췄습니다. 저장된 관측을 확인하세요.'; break }
      if (chosen.value.remaining === 0) { notice.value = '선택한 케이스의 실제 HTTP 관측 목표를 채웠습니다.'; break }
      await new Promise(resolve => setTimeout(resolve, entry.conditions.intervalMs))
    }
  } catch (e) {
    error.value = e.name === 'AbortError' ? '브라우저 전송을 중단했습니다. 서버 저장 상태를 확인하세요.' : e.message || errorMessage(e)
  } finally {
    targets.candidateKey = ''; inputs.candidateKey = ''; runId = null; busy.value = false; approved.value = false; emit('busy', false)
    await refresh()
  }
}
async function stop() {
  stopped = true
  try { if (runId) await postWithCsrf(`/admin/reliability-lab/experiments/${runId}/cancel`) }
  catch (e) { error.value = errorMessage(e) }
}
function loadResult(row) { emit('result', row.result); notice.value = '서버에 저장된 실행 결과를 아래 분석 화면에 불러왔습니다.' }
</script>
<template>
  <section class="portal-panel">
    <h3>케이스별 반복 측정 · 서버 자동 저장</h3>
    <Message v-if="!caps?.recordingEnabled" severity="info" :closable="false">V4 SQL 적용 및 LAB_RECORDING_ENABLED 활성화 후 사용할 수 있습니다. 기존 실험은 그대로 사용할 수 있습니다.</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <p>후속 탐색은 한 절차씩 실행합니다. 각 숫자는 최대 HTTP 예산이며 실제 표본 수가 아닙니다. 초기 성공이면 재시도를 생략하며, 미사용 예산을 채우기 위한 추가 호출은 하지 않습니다. 계획 저장은 API를 호출하지 않습니다.</p>
    <div class="lab-field"><label for="campaign-kind">저장할 실험 계획</label><select id="campaign-kind" v-model="planKind" :disabled="busy || blocked"><option v-for="p in followupPlans" :key="p.id" :value="p.id">{{ p.label }}</option><option value="ORIGINAL">기존 11조건 · 조건별 100 HTTP</option></select></div>
    <p>{{ plannedCases.length }}개 절차 · 최대 HTTP {{ planBudget }}건. B01 → T01 정상 확인 후 필요한 가족만 별도로 실행합니다. 첫 탐색은 B01/T01/R01/R02/P01/C01 합계 최대 55건이며 P02는 P01의 별도 승인 대체안입니다.</p>
    <div class="portal-actions">
      <Button label="선택 계획 저장 (외부 호출 없음)" :disabled="!caps?.recordingEnabled || busy || blocked" @click="create" />
      <Button label="저장 목록 새로고침" severity="secondary" :disabled="!caps?.recordingEnabled || busy || blocked" @click="refresh" />
    </div>
    <div class="lab-field"><label for="campaign-plan">저장된 계획</label><select id="campaign-plan" v-model="selectedPlan" :disabled="busy || blocked" @change="loadPlan"><option value="">선택</option><option v-for="p in plans" :key="p.campaignId" :value="p.campaignId">{{ p.campaignId }}</option></select></div>
    <DataTable :value="cases"><Column field="label" header="측정 조건" /><Column field="observed" header="실제 HTTP n" /><Column field="remaining" header="미사용 예산" /><Column header="상태"><template #body="{data}">{{ data.unresolved ? '최종 상태 확인 필요' : data.procedureDone ? '절차 종료 · 결과 검토' : data.remaining ? '대기/중단' : '목표 도달' }}</template></Column></DataTable>
    <form class="lab-grid" @submit.prevent="execute">
      <div class="lab-wide lab-field"><label for="campaign-case">실행할 케이스</label><select id="campaign-case" v-model="selectedCase" :disabled="busy || blocked"><option v-for="c in cases" :key="c.caseId" :value="c.caseId">{{ c.label }}</option></select></div>
      <div class="lab-field"><label for="campaign-key">이번 실행의 X-Candidate-Key</label><InputText id="campaign-key" v-model="inputs.candidateKey" type="password" autocomplete="off" :disabled="busy || blocked" /></div>
      <div class="lab-field"><label for="campaign-employee">가상 employeeId (재개 시 동일 값)</label><InputText id="campaign-employee" v-model="inputs.employeeId" :disabled="busy || blocked" /></div>
      <div v-if="chosen?.conditions.endpoint === 'DETAIL'" class="lab-field"><label for="campaign-check">실제 유효한 최종 checkId (화면 입력·실행 중에만 사용)</label><InputText id="campaign-check" v-model="inputs.checkId" :disabled="busy || blocked" /></div>
      <template v-if="chosen && post">
        <div class="lab-field"><label for="campaign-first">가상 이름</label><InputText id="campaign-first" v-model="inputs.firstName" :disabled="busy || blocked" /></div>
        <div class="lab-field"><label for="campaign-last">가상 성</label><InputText id="campaign-last" v-model="inputs.lastName" :disabled="busy || blocked" /></div>
        <div class="lab-field"><label for="campaign-birth">가상 생년월일</label><InputText id="campaign-birth" v-model="inputs.dateOfBirth" type="date" :disabled="busy || blocked" /></div>
      </template>
      <div class="lab-wide"><label><input v-model="approved" type="checkbox" :disabled="busy || blocked" /> 선택한 조건·남은 HTTP 예산 및 POST 기록 생성을 확인했습니다.</label>
        <p>선택한 케이스만 실행합니다. 네트워크 오류·503/429·Retry-After·미완료 Polling·저장 실패에서 멈춥니다. POST 결과 불명 시 재전송하지 않습니다. 재개는 명시적 실행이며 Retry 성공률 실험으로 간주하지 않습니다.</p>
        <p v-if="chosen?.conditions.protocol">후속 예외: R01/R03은 자연 발생 GET 500만, R02는 GET 503만, R04는 GET 요청 타임아웃만 후속 GET 대상입니다. 401/404/429·계약 오류·저장 실패에서는 중단합니다. 대기 안내와 다음 요청 상한이 120초 창에 들어가지 않으면 후속 호출을 생략합니다. POST는 자동 재시도하지 않습니다.</p>
        <p>키는 실행 중 메모리에만 유지하고 종료·중단·화면 이탈 시 지웁니다. 개인정보·대상 ID·응답 상세는 서버에 저장하지 않습니다. 같은 관리자 계정으로 다른 PC에서 측정 메타데이터를 조회할 수 있습니다.</p>
        <p v-if="chosen?.conditions.protocol?.family === 'P01'">P01: LIST 1회에서 첫 pending을 선택해 같은 checkId만 최대 6회 관찰합니다. 첫 DETAIL은 즉시, 이후 응답 후 5초 대기합니다. pending이 없으면 생성하지 않고 종료합니다.</p>
        <p v-if="['C01','C02'].includes(chosen?.conditions.protocol?.family)">균형 순서: 1→2→3 / 2→3→1 / 3→1→2. 수준별 GET 3건, 묶음 사이 2초, 수준 사이 최소 30초. 서버가 선행 절차 완료·순서를 검사하고 오류 뒤 다음 수준을 차단합니다. 동시성 2의 마지막 1건은 부분 묶음입니다.</p>
        <p>생성 폴링은 POST 응답 후 선택한 폴링 간격을 기다립니다. 즉시 최종이면 GET 0회, 최대 횟수·120초·오류에서 중단합니다. 기존 중복 실험은 이력 GET → POST → 2초 → POST → 이력 GET입니다.</p>
      </div>
      <div class="lab-wide portal-actions"><Button label="선택 케이스 실행/재개" type="submit" :disabled="!ready" :loading="busy" /><Button label="중단" severity="danger" :disabled="!busy" @click="stop" /></div>
    </form>
    <p v-if="notice">{{ notice }}</p>
    <p>새로고침하면 다음 차수는 자동 시작하지 않습니다. 서버가 중단·재배포되어 RUNNING이 남으면 호출 수가 미확정이므로 재개를 차단합니다. 실제 키·대상 변경 시 비교 조건도 달라지므로 새 계획을 만드세요.</p>
    <div class="portal-actions"><Button label="계획·관측 JSON 다운로드" :disabled="!saved || busy" @click="download('campaign-'+selectedPlan+'.json',bundle,'application/json')" /><Button label="JSON 텍스트 보기" severity="secondary" :disabled="!saved || busy" @click="text=!text" /></div>
    <textarea v-if="text" :value="bundle" readonly rows="12" aria-label="서버 저장 계획과 원본 관측 JSON" style="width:100%" />
    <h4>후속 조건별 근거 (실제 저장 실행)</h4>
    <p>같은 대상·키·환경은 저장하지 않으므로 실행자가 확인해야 합니다. 아래는 실행별 정상 HTTP 지연이며 순서·부분 묶음·불완전 블록을 함께 해석합니다. 서로 다른 조건의 p99를 합치지 않습니다. 처리량은 대기를 포함한 관측 경과 기준이며 최대 처리능력 추정이 아닙니다.</p>
    <DataTable :value="comparisons"><Column field="family" header="가족"/><Column field="block" header="블록"/><Column field="order" header="순서"/><Column field="endpoint" header="Endpoint"/><Column field="concurrency" header="동시성"/><Column field="n" header="실제 시도 n"/><Column field="failedN" header="오류 n"/><Column header="정상 n / p95 ms"><template #body="{data}">{{data.successLatency.n}} / {{display(data.successLatency.p95)}}</template></Column><Column field="widths" header="묶음 폭"/><Column field="maxClientCallsInFlight" header="Client 겹침 최대"/><Column header="정상 응답/초"><template #body="{data}">{{display(data.observedThroughput)}}</template></Column><Column field="reason" header="중단"/></DataTable>
    <Message severity="info" :closable="false">P03은 아래 기존 폴링 시뮬레이터에서 유효 서버 업무 시간으로 비교합니다(외부 호출 0). P05는 창 간 실제 동일 조회 검증이 필요하고, N01은 창 간 Client 재사용 계측이 필요하여 실행을 제공하지 않습니다. LOCAL_MOCK의 회복률은 외부 API 판단 근거가 아닙니다.</Message>
    <h4>최근 서버 저장 실행 (최대 100차수)</h4>
    <DataTable :value="records"><Column field="runId" header="실행 ID" /><Column field="recordingStatus" header="상태" /><Column header="관측 n"><template #body="{data}">{{ data.result.attempts?.length || 0 }}</template></Column><Column header="분석"><template #body="{data}"><Button label="불러오기" severity="secondary" :disabled="busy || blocked" @click="loadResult(data)" /></template></Column></DataTable>
  </section>
</template>

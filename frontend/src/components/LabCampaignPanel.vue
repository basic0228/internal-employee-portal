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
const props = defineProps({ caps: Object, blocked: Boolean })
const emit = defineEmits(['busy', 'result'])
const plans = ref([]), selectedPlan = ref(''), selectedCase = ref(''), saved = ref(null), records = ref([])
const busy = ref(false), error = ref(''), notice = ref(''), approved = ref(false), text = ref(false)
const inputs = reactive({ candidateKey: '', employeeId: '', checkId: '', firstName: '', lastName: '', dateOfBirth: '' })
let abort, stopped = false, runId
const cases = computed(() => (saved.value?.plan.cases || []).map(c => ({ ...c, ...caseProgress(c, saved.value.runs), label: caseLabel(c.conditions) })))
const chosen = computed(() => cases.value.find(c => c.caseId === selectedCase.value))
const bundle = computed(() => saved.value ? JSON.stringify(saved.value, null, 2) : '')
const post = computed(() => chosen.value?.conditions.type !== 'GET_FIXED')
const ready = computed(() => props.caps?.enabled && props.caps?.recordingEnabled && chosen.value?.remaining > 0 && !chosen.value.unresolved &&
  !busy.value && !props.blocked && approved.value && inputs.candidateKey.trim() && inputs.employeeId &&
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
    const { data } = await postWithCsrf('/admin/reliability-lab/campaigns', { cases: defaultCases() })
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
      if (!entry || entry.remaining === 0 || entry.unresolved) break
      const request = nextRequest(entry.conditions, entry.remaining, targets)
      const result = await streamExperiment(request, event => {
        if (event.data.run?.runId) runId = event.data.run.runId
        if (event.type === 'result') { runId = null; emit('result', event.data) }
        if (event.type === 'progress') notice.value = `현재 차수 관측 ${event.data.run.finished}건 · 서버에 자동 저장 중`
      }, abort.signal, selectedPlan.value, entry.caseId)
      await loadPlan()
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
    <p>GET 10·30·60초 × 동시성 1·2·3의 9조건, POST+Polling, 중복 POST를 합쳐 11조건입니다. 조건마다 GET·POST·오류 포함 HTTP 100건, 전체 상한 1,100건입니다. 계획 저장은 API를 호출하지 않습니다.</p>
    <div class="portal-actions">
      <Button label="11케이스 계획 저장" :disabled="!caps?.recordingEnabled || busy || blocked" @click="create" />
      <Button label="저장 목록 새로고침" severity="secondary" :disabled="!caps?.recordingEnabled || busy || blocked" @click="refresh" />
    </div>
    <div class="lab-field"><label for="campaign-plan">저장된 계획</label><select id="campaign-plan" v-model="selectedPlan" :disabled="busy || blocked" @change="loadPlan"><option value="">선택</option><option v-for="p in plans" :key="p.campaignId" :value="p.campaignId">{{ p.campaignId }}</option></select></div>
    <DataTable :value="cases"><Column field="label" header="측정 조건" /><Column field="observed" header="실제 HTTP n" /><Column field="remaining" header="미시행" /><Column header="상태"><template #body="{data}">{{ data.unresolved ? '최종 상태 확인 필요' : data.remaining ? '대기/중단' : '목표 도달' }}</template></Column></DataTable>
    <form class="lab-grid" @submit.prevent="execute">
      <div class="lab-wide lab-field"><label for="campaign-case">실행할 케이스</label><select id="campaign-case" v-model="selectedCase" :disabled="busy || blocked"><option v-for="c in cases" :key="c.caseId" :value="c.caseId">{{ c.label }}</option></select></div>
      <div class="lab-field"><label for="campaign-key">이번 실행의 X-Candidate-Key</label><InputText id="campaign-key" v-model="inputs.candidateKey" type="password" autocomplete="off" :disabled="busy || blocked" /></div>
      <div class="lab-field"><label for="campaign-employee">가상 employeeId (재개 시 동일 값)</label><InputText id="campaign-employee" v-model="inputs.employeeId" :disabled="busy || blocked" /></div>
      <template v-if="chosen && post">
        <div class="lab-field"><label for="campaign-first">가상 이름</label><InputText id="campaign-first" v-model="inputs.firstName" :disabled="busy || blocked" /></div>
        <div class="lab-field"><label for="campaign-last">가상 성</label><InputText id="campaign-last" v-model="inputs.lastName" :disabled="busy || blocked" /></div>
        <div class="lab-field"><label for="campaign-birth">가상 생년월일</label><InputText id="campaign-birth" v-model="inputs.dateOfBirth" type="date" :disabled="busy || blocked" /></div>
      </template>
      <div class="lab-wide"><label><input v-model="approved" type="checkbox" :disabled="busy || blocked" /> 선택한 조건·남은 HTTP 예산 및 POST 기록 생성을 확인했습니다.</label>
        <p>선택한 케이스만 실행합니다. 네트워크 오류·503/429·Retry-After·미완료 Polling·저장 실패에서 멈춥니다. POST 결과 불명 시 재전송하지 않습니다. 재개는 명시적 실행이며 Retry 성공률 실험으로 간주하지 않습니다.</p>
        <p>키는 실행 중 메모리에만 유지하고 종료·중단·화면 이탈 시 지웁니다. 개인정보·대상 ID·응답 상세는 서버에 저장하지 않습니다. 같은 관리자 계정으로 다른 PC에서 측정 메타데이터를 조회할 수 있습니다.</p>
        <p>Polling: POST 1 + 최대 상세 GET 5, 응답 수신 후 5초 대기, 차수 최대 120초. 중복: 이력 GET → 동일 입력 POST → 2초 → POST → 이력 GET. 잔여 예산이 절차보다 적으면 불완전 관찰로 표시합니다.</p>
      </div>
      <div class="lab-wide portal-actions"><Button label="선택 케이스 실행/재개" type="submit" :disabled="!ready" :loading="busy" /><Button label="중단" severity="danger" :disabled="!busy" @click="stop" /></div>
    </form>
    <p v-if="notice">{{ notice }}</p>
    <p>새로고침하면 다음 차수는 자동 시작하지 않습니다. 서버가 중단·재배포되어 RUNNING이 남으면 호출 수가 미확정이므로 재개를 차단합니다. 실제 키·대상 변경 시 비교 조건도 달라지므로 새 계획을 만드세요.</p>
    <div class="portal-actions"><Button label="계획·관측 JSON 다운로드" :disabled="!saved || busy" @click="download('campaign-'+selectedPlan+'.json',bundle,'application/json')" /><Button label="JSON 텍스트 보기" severity="secondary" :disabled="!saved || busy" @click="text=!text" /></div>
    <textarea v-if="text" :value="bundle" readonly rows="12" aria-label="서버 저장 계획과 원본 관측 JSON" style="width:100%" />
    <h4>최근 서버 저장 실행 (최대 100차수)</h4>
    <DataTable :value="records"><Column field="runId" header="실행 ID" /><Column field="recordingStatus" header="상태" /><Column header="관측 n"><template #body="{data}">{{ data.result.attempts?.length || 0 }}</template></Column><Column header="분석"><template #body="{data}"><Button label="불러오기" severity="secondary" :disabled="busy || blocked" @click="loadResult(data)" /></template></Column></DataTable>
  </section>
</template>

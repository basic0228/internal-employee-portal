<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Message from 'primevue/message'
import { blocksCreation, findings } from '../backgroundcheck/policy'

const props = defineProps({
  visible: Boolean, employee: Object,
  history: { type: Array, default: () => [] },
  historyLoaded: { type: Boolean, default: false },
  available: { type: Boolean, default: false },
  historyError: { type: String, default: '' }, detail: { type: Object, default: null },
  busy: Boolean, views: { type: Array, default: () => [] }, offset: Number, total: Number,
  externalChecks: { type: Array, default: () => [] }, unknownRequest: String,
})
const emit = defineEmits(['update:visible', 'request', 'detail', 'inspect', 'resolve', 'page', 'refresh'])
const form = reactive({ firstName: '', lastName: '', dateOfBirth: '', confirmRecheck: false })
const blocking = computed(() => props.history.some(blocksCreation))
const needsConfirmation = computed(() => props.total > 0)
const candidate = reactive({ checkId: '', confirmed: false })
const detailPanel = ref(null), detailTarget = ref(null)
watch(() => [props.visible, props.employee?.employeeId], () => {
  // Never guess a Korean surname boundary. Query inputs do not update the employee.
  form.firstName = ''
  form.lastName = ''
  form.dateOfBirth = props.employee?.dateOfBirth || ''
  form.confirmRecheck = false;candidate.checkId = '';candidate.confirmed = false
  detailTarget.value = null
}, { immediate: true })
const valid = computed(() => form.firstName.trim() && form.lastName.trim() && form.dateOfBirth)
const states = {
  pending: { label: '조회 중 · 특이사항 미확정', severity: 'info' },
  clear: { label: '특이사항 없음', severity: 'success' },
  flagged: { label: '검토 필요', severity: 'warn' },
}
const state = value => states[value] || { label: '확인 불가', severity: 'secondary' }
function showDetail(row) {
  detailTarget.value = { checkId: row.checkId, requestedAt: row.requestedAt }
  emit('detail', row.requestId)
  // The result stays in this modal; bring it into view even for a long history list.
  nextTick(() => detailPanel.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}
function submit() {
  if (!props.available || !valid.value || !props.employee || props.busy || blocking.value || needsConfirmation.value && !form.confirmRecheck) return
  detailTarget.value = null
  emit('request', {
    firstName: form.firstName.trim(), lastName: form.lastName.trim(),
    dateOfBirth: form.dateOfBirth, confirmRecheck: form.confirmRecheck,
  })
}
</script>

<template>
  <Dialog :visible="visible" @update:visible="emit('update:visible', $event)"
    modal header="배경 조회" :style="{ width: '64rem', maxWidth: '95vw' }">
    <p>{{ employee?.employeeId }} · {{ employee?.fullName }}</p>
    <section ref="detailPanel" class="portal-check-detail" aria-labelledby="check-detail-heading" aria-live="polite" :aria-busy="Boolean(detailTarget && busy)">
      <h3 id="check-detail-heading">조회 상세</h3>
      <p v-if="detailTarget" class="portal-check-reference">조회 번호: {{ detailTarget.checkId }}<br />요청 시각: {{ detailTarget.requestedAt }}</p>
      <p v-if="detailTarget && busy" role="status">외부 API에서 상세 결과를 확인하고 있습니다.</p>
      <template v-else-if="detail">
        <p>상태: {{ state(detail.status).label }}</p>
        <Message v-if="detail.status === 'pending'" severity="info" :closable="false">아직 처리 중입니다. 완료 전의 빈 항목은 특이사항 없음이나 검증 완료를 뜻하지 않습니다.</Message>
        <dl class="portal-details">
          <dt>완료 시각</dt><dd>{{ detail.completedAt || '확인되지 않음' }}</dd>
          <dt>범죄 이력</dt><dd>{{ detail.criminalRecord === true ? '확인됨' : detail.criminalRecord === false ? '없음' : '미확인' }}</dd>
          <dt>학력 확인</dt><dd>{{ detail.educationVerified === true ? '확인' : detail.educationVerified === false ? '미확인' : '결과 없음' }}</dd>
          <dt>경력 확인</dt><dd>{{ detail.employmentVerified === true ? '확인' : detail.employmentVerified === false ? '미확인' : '결과 없음' }}</dd>
          <dt>신용 등급</dt><dd>{{ detail.creditScore || '결과 없음' }}</dd>
        </dl>
        <p>상세 내용은 이 화면에서만 열람하며 저장하지 않습니다.</p>
      </template>
      <Message v-else-if="detailTarget" severity="warn" :closable="false">{{ historyError || '상세 결과를 받지 못했습니다. 이력의 상태만으로 상세 내용을 확인할 수는 없습니다.' }}</Message>
      <p v-else>아래 이력의 ‘상세 결과 보기’를 누르면 해당 조회의 실제 결과를 불러옵니다. 신규 조회나 자동 상태 확인은 상세 내용을 열지 않습니다.</p>
    </section>
    <form class="portal-form" @submit.prevent="submit">
      <label for="check-last-name">조회용 성</label>
      <InputText id="check-last-name" v-model="form.lastName" required maxlength="100" />
      <label for="check-first-name">조회용 이름</label>
      <InputText id="check-first-name" v-model="form.firstName" required maxlength="100" />
      <label for="check-birth">조회용 생년월일</label>
      <InputText id="check-birth" v-model="form.dateOfBirth" type="date" required />
      <label v-if="needsConfirmation"><input type="checkbox" v-model="form.confirmRecheck" /> 기존 결과 열람과 별도로 새 배경 조회를 생성합니다.</label>
      <Button type="submit" label="신규 조회" :loading="busy" :disabled="!available || !valid || blocking || needsConfirmation && !form.confirmRecheck" />
      <Message v-if="!available" severity="info" :closable="false">
        조회 이력 SQL 및 서버 활성화 설정을 확인해야 합니다. 현재 신규 요청은 전송되지 않습니다.
      </Message>
      <Message v-if="blocking" severity="warn" :closable="false">진행 중이거나 결과 불명인 요청이 있습니다. 새 요청 대신 기존 조회를 확인하세요.</Message>
    </form>
    <p>관리자 수동 조회 · 상세 내용 미저장 · 이력 삭제 없음. 자동 상태 확인은 이 화면이 열린 동안만 진행하며, 오류나 대기 상한 도달 시 중단합니다.</p>
    <h3>기존 조회 이력</h3>
    <Button label="이력 새로고침" text :disabled="busy" @click="emit('refresh')" />
    <Message v-if="historyError && (!detailTarget || detail)" severity="warn" :closable="false">{{ historyError }}</Message>
    <Message v-if="!historyLoaded" severity="info" :closable="false">조회 이력이 아직 연결되지 않았습니다.</Message>
    <DataTable v-else :value="history" dataKey="requestId" class="portal-check-history">
      <template #empty>기존 조회 이력이 없습니다.</template>
      <Column header="결과">
        <template #body="{ data }">
          <Button v-if="data.checkId" label="상세 결과 보기" text :disabled="busy" @click="showDetail(data)" />
          <Button v-if="data.localState === 'UNKNOWN'" label="외부 이력 대조" text :disabled="busy" @click="emit('inspect', data.requestId)" />
        </template>
      </Column>
      <Column field="requestedAt" header="조회 요청 시각" />
      <Column field="requestedByUsername" header="조회 계정" />
      <Column field="employeeId" header="조회 대상" />
      <Column field="checkId" header="조회 번호" />
      <Column header="상태 및 특이사항">
        <template #body="{ data }">
          <Tag :value="data.localState === 'UNKNOWN' ? '생성 여부 불명' : data.localState === 'DISPATCHING' ? '요청 전송 중' : findings(data.status)" :severity="state(data.status).severity" />
          <p v-if="data.pollStopped && data.status === 'pending'">자동 확인 중단 · 결과 미확정</p>
          <p v-if="['REJECTED', 'NOT_SENT'].includes(data.localState)">{{ data.localState === 'REJECTED' ? '요청 거절' : '전송되지 않음' }}</p>
          <p v-if="data.lastHttpCode >= 400">최근 확인: HTTP {{ data.lastHttpCode }}</p>
          <p v-else-if="data.lastOutcome === 'REQUEST_TIMEOUT'">최근 확인: 요청 시간 초과</p>
        </template>
      </Column>
      <Column field="observedAt" header="최근 결과 확인 시각" />
    </DataTable>
    <div class="portal-actions"><Button label="이전" text :disabled="!offset || busy" @click="emit('page', Math.max(0, offset - 100))" />
      <span>{{ total || 0 }}건 · {{ (offset || 0) + 1 }}부터</span><Button label="다음" text :disabled="offset + 100 >= total || busy" @click="emit('page', offset + 100)" /></div>
    <section v-if="unknownRequest">
      <h3>결과 불명 요청 연결</h3>
      <p>아래 외부 이력은 생성 여부의 자동 증명이 아닙니다. 요청 시각과 대상을 대조해 연결할 기록을 선택하세요. 확인할 수 없으면 결과 불명을 유지합니다.</p>
      <select v-model="candidate.checkId" aria-label="연결할 외부 조회"><option value="">조회 선택</option>
        <option v-for="item in externalChecks" :key="item.checkId" :value="item.checkId">{{ item.checkId }} · {{ item.createdAt }} · {{ item.status }}</option></select>
      <label><input type="checkbox" v-model="candidate.confirmed" /> 대상과 시각을 확인했고 이 기록을 연결합니다.</label>
      <Button label="선택한 이력 연결" :disabled="!candidate.checkId || !candidate.confirmed || busy" @click="emit('resolve', candidate.checkId)" />
    </section>
    <section v-if="views.length"><h3>최근 열람 이력</h3><DataTable :value="views"><Column field="viewed_at" header="열람 시각" />
      <Column field="username" header="열람 계정" /><Column field="action" header="작업" /><Column field="outcome" header="확인 결과" /></DataTable></section>
  </Dialog>
</template>

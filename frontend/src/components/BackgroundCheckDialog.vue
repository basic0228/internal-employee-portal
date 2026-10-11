<script setup>
import { computed, reactive, watch } from 'vue'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import Message from 'primevue/message'

const props = defineProps({
  visible: Boolean, employee: Object,
  history: { type: Array, default: () => [] },
  historyLoaded: { type: Boolean, default: false },
  available: { type: Boolean, default: false },
  historyError: { type: String, default: '' }, detail: { type: Object, default: null },
})
const emit = defineEmits(['update:visible', 'request', 'detail'])
const form = reactive({ firstName: '', lastName: '', dateOfBirth: '' })
watch(() => [props.visible, props.employee?.employeeId], () => {
  // Never guess a Korean surname boundary. Query inputs do not update the employee.
  form.firstName = ''
  form.lastName = ''
  form.dateOfBirth = props.employee?.dateOfBirth || ''
}, { immediate: true })
const valid = computed(() => form.firstName.trim() && form.lastName.trim() && form.dateOfBirth)
const states = {
  pending: { label: '조회 중 · 특이사항 미확정', severity: 'info' },
  clear: { label: '특이사항 없음', severity: 'success' },
  flagged: { label: '검토 필요', severity: 'warn' },
}
const state = value => states[value] || { label: '확인 불가', severity: 'secondary' }
function submit() {
  if (!props.available || !valid.value || !props.employee) return
  emit('request', {
    employeeId: props.employee.employeeId,
    firstName: form.firstName.trim(), lastName: form.lastName.trim(),
    dateOfBirth: form.dateOfBirth,
  })
}
</script>

<template>
  <Dialog :visible="visible" @update:visible="emit('update:visible', $event)"
    modal header="배경 조회" :style="{ width: '48rem', maxWidth: '95vw' }">
    <p>{{ employee?.employeeId }} · {{ employee?.fullName }}</p>
    <form class="portal-form" @submit.prevent="submit">
      <label for="check-last-name">조회용 성</label>
      <InputText id="check-last-name" v-model="form.lastName" required maxlength="100" />
      <label for="check-first-name">조회용 이름</label>
      <InputText id="check-first-name" v-model="form.firstName" required maxlength="100" />
      <label for="check-birth">조회용 생년월일</label>
      <InputText id="check-birth" v-model="form.dateOfBirth" type="date" required />
      <Button type="submit" label="조회" :disabled="!available || !valid" />
      <Message v-if="!available" severity="info" :closable="false">
        배경 조회 생성 정책은 API 실측 후 확정합니다. 현재 POST 생성 요청은 전송되지 않습니다.
      </Message>
    </form>
    <h3>기존 조회 이력</h3>
    <Message v-if="historyError" severity="warn" :closable="false">{{ historyError }}</Message>
    <Message v-if="!historyLoaded" severity="info" :closable="false">조회 이력이 아직 연결되지 않았습니다.</Message>
    <DataTable v-else :value="history" dataKey="checkId">
      <template #empty>기존 조회 이력이 없습니다.</template>
      <Column field="createdAt" header="조회 요청 시각" />
      <Column field="checkId" header="조회 번호" />
      <Column header="상태 및 특이사항">
        <template #body="{ data }"><Tag :value="state(data.status).label" :severity="state(data.status).severity" /></template>
      </Column>
      <Column header="결과">
        <template #body="{ data }"><Button label="상세" text @click="emit('detail', data.checkId)" /></template>
      </Column>
    </DataTable>
    <section v-if="detail">
      <h3>조회 상세</h3>
      <p>상태: {{ state(detail.status).label }}</p>
      <dl class="portal-details">
        <dt>완료 시각</dt><dd>{{ detail.completedAt || '확인되지 않음' }}</dd>
        <dt>범죄 이력</dt><dd>{{ detail.criminalRecord === true ? '확인됨' : detail.criminalRecord === false ? '없음' : '미확인' }}</dd>
        <dt>학력 확인</dt><dd>{{ detail.educationVerified === true ? '확인' : detail.educationVerified === false ? '미확인' : '결과 없음' }}</dd>
        <dt>경력 확인</dt><dd>{{ detail.employmentVerified === true ? '확인' : detail.employmentVerified === false ? '미확인' : '결과 없음' }}</dd>
        <dt>신용 등급</dt><dd>{{ detail.creditScore || '결과 없음' }}</dd>
      </dl>
    </section>
  </Dialog>
</template>

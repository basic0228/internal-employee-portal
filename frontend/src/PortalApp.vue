<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Tag from 'primevue/tag'
import { api, errorMessage, onUnauthorized, postWithCsrf, putWithCsrf } from './api/portal'
import { useAuthStore } from './stores/auth'
import EmployeeCreateDialog from './components/EmployeeCreateDialog.vue'
import BackgroundCheckDialog from './components/BackgroundCheckDialog.vue'
import ReliabilityLab from './components/ReliabilityLab.vue'

const auth = useAuthStore()
const loginForm = reactive({ username: '', password: '' })
const error = ref('')
const busy = ref(false)
const ready = ref(false)
const employees = ref([])
const myself = ref(null)
const contactForm = reactive({ email: '', phone: '' })
const contactSaved = ref(false)
const createVisible = ref(false)
const selected = ref(null)
const checkVisible = ref(false)
const checkHistory = ref([]), historyLoaded = ref(false), historyError = ref(''), checkDetail = ref(null)
const terminationTarget = ref(null)
const labVisible = ref(false)
function clearPrivateData() {
  auth.clear(); employees.value = []; myself.value = null
  labVisible.value = false
  contactForm.email = ''; contactForm.phone = ''; contactSaved.value = false
  selected.value = null; checkVisible.value = false; createVisible.value = false; terminationTarget.value = null
  checkHistory.value = []; historyLoaded.value = false; historyError.value = ''; checkDetail.value = null
}
onUnauthorized(clearPrivateData)
onUnmounted(() => onUnauthorized(() => {}))
async function load() {
  if (auth.user?.role === 'ADMIN') employees.value = (await api.get('/admin/employees')).data
  else if (auth.user) {
    myself.value = (await api.get('/me')).data
    contactForm.email = myself.value.email || ''
    contactForm.phone = myself.value.phone || ''
  }
}
onMounted(async () => {
  try { await auth.restore(); await load() } catch (failure) { error.value = errorMessage(failure) }
  finally { ready.value = true }
})
async function login() {
  busy.value = true; error.value = ''
  try { await auth.login(loginForm.username, loginForm.password); await load() }
  catch (failure) { error.value = errorMessage(failure) }
  finally { loginForm.password = ''; busy.value = false }
}
async function logout() {
  error.value = ''
  try { await auth.logout(); clearPrivateData() }
  catch (failure) { error.value = errorMessage(failure) }
}
async function reload() {
  try { await load() } catch (failure) { error.value = errorMessage(failure) }
}
async function saveContact() {
  busy.value = true; error.value = ''; contactSaved.value = false
  try {
    // Only contact fields are sent; employee identity comes from the server session.
    myself.value = (await putWithCsrf('/me/contact', {
      email: contactForm.email, phone: contactForm.phone,
    })).data
    contactForm.email = myself.value.email || ''
    contactForm.phone = myself.value.phone || ''
    contactSaved.value = true
  } catch (failure) { error.value = errorMessage(failure) }
  finally { busy.value = false }
}
async function showCheck(employee) {
  selected.value = employee; checkVisible.value = true; checkHistory.value = []; historyLoaded.value = false; historyError.value = ''; checkDetail.value = null
  try {
    const response = await api.get('/admin/employees/' + encodeURIComponent(employee.employeeId) + '/background-checks')
    if (checkVisible.value && selected.value?.employeeId === employee.employeeId) { checkHistory.value = response.data.checks; historyLoaded.value = true }
  } catch (failure) { historyError.value = errorMessage(failure) }
}
async function showCheckDetail(checkId) {
  if (!selected.value) return
  try { checkDetail.value = (await api.get('/admin/employees/' + encodeURIComponent(selected.value.employeeId) + '/background-checks/' + encodeURIComponent(checkId))).data }
  catch (failure) { historyError.value = errorMessage(failure) }
}
async function terminate() {
  if (!terminationTarget.value) return
  busy.value = true; error.value = ''
  try {
    await postWithCsrf('/admin/employees/' + encodeURIComponent(terminationTarget.value.employeeId) + '/termination')
    terminationTarget.value = null
    await load()
  } catch (failure) { error.value = errorMessage(failure) }
  finally { busy.value = false }
}
</script>

<template>
  <main class="portal-shell">
    <header class="portal-header">
      <div><p class="portal-eyebrow">INTERNAL EMPLOYEE PORTAL</p><h1>직원 관리 시스템</h1></div>
      <div v-if="auth.user" class="portal-actions">
        <span>{{ auth.user.username }} · {{ auth.user.role === 'ADMIN' ? '관리자' : '직원' }}</span>
        <Button label="로그아웃" severity="secondary" @click="logout" />
      </div>
    </header>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <p v-if="!ready">불러오는 중입니다.</p>
    <section v-else-if="!auth.user" class="portal-login">
      <h2>로그인</h2>
      <form class="portal-form" @submit.prevent="login">
        <label for="username">아이디</label><InputText id="username" v-model="loginForm.username" required autocomplete="username" />
        <label for="password">비밀번호</label><InputText id="password" v-model="loginForm.password" required type="password" autocomplete="current-password" />
        <Button label="로그인" type="submit" :loading="busy" />
      </form>
      <p>계정 발급은 관리자에게 문의하세요.</p>
    </section>
    <ReliabilityLab v-else-if="auth.user.role === 'ADMIN' && labVisible" :employees="employees" @close="labVisible = false" />
    <section v-else-if="auth.user.role === 'ADMIN'" class="portal-panel">
      <div class="portal-section-title"><h2>직원 목록</h2><div class="portal-actions"><Button label="API Reliability Lab" severity="secondary" @click="labVisible = true" /><Button label="직원 계정 생성" icon="pi pi-plus" @click="createVisible = true" /></div></div>
      <div v-if="terminationTarget" class="portal-confirm">
        <p>{{ terminationTarget.employeeId }} · {{ terminationTarget.fullName }} 직원을 퇴사 처리하시겠습니까?</p>
        <p>계정 사용이 제한되고 기존 세션은 다음 API 접근 시 차단됩니다.</p>
        <div class="portal-actions"><Button label="퇴사 처리 확인" severity="danger" :loading="busy" @click="terminate" /><Button label="취소" severity="secondary" @click="terminationTarget = null" /></div>
      </div>
      <DataTable :value="employees" dataKey="employeeId" stripedRows scrollable :tableStyle="{ minWidth: '48rem' }">
        <template #empty>표시할 직원이 없습니다.</template>
        <Column field="employeeId" header="사번" />
        <Column field="fullName" header="성명" />
        <Column header="생년월일"><template #body="{ data }">{{ data.dateOfBirth || '확인되지 않음' }}</template></Column>
        <Column header="재직 상태"><template #body="{ data }"><Tag :value="data.employmentStatus === 'ACTIVE' ? '재직' : '퇴사'" :severity="data.employmentStatus === 'ACTIVE' ? 'success' : 'secondary'" /></template></Column>
        <Column header="관리"><template #body="{ data }">
          <div class="portal-actions">
            <Button label="배경 조회" size="small" text @click="showCheck(data)" />
            <Button v-if="data.employmentStatus === 'ACTIVE'" label="퇴사 처리" size="small" severity="danger" text @click="terminationTarget = data" />
          </div>
        </template></Column>
      </DataTable>
      <EmployeeCreateDialog v-model:visible="createVisible" @created="reload" />
      <BackgroundCheckDialog v-model:visible="checkVisible" :employee="selected" :history="checkHistory" :historyLoaded="historyLoaded" :historyError="historyError" :detail="checkDetail" @detail="showCheckDetail" />
    </section>
    <section v-else-if="myself" class="portal-panel">
      <h2>내 정보</h2>
      <dl class="portal-details">
        <dt>사번</dt><dd>{{ myself.employeeId }}</dd>
        <dt>성명</dt><dd>{{ myself.fullName }}</dd>
        <dt>생년월일</dt><dd>{{ myself.dateOfBirth || '확인되지 않음' }}</dd>
        <dt>최종 수정 시각</dt><dd>{{ myself.updatedAt }}</dd>
      </dl>
      <h2>연락처 수정</h2>
      <p>이메일과 전화번호를 수정할 수 있습니다. 빈 값으로 저장하면 해당 연락처를 삭제합니다.</p>
      <Message v-if="contactSaved" severity="success" :closable="false">연락처를 저장했습니다.</Message>
      <form class="portal-form portal-contact-form" @submit.prevent="saveContact" @input="contactSaved = false">
        <label for="contact-email">이메일</label>
        <InputText id="contact-email" v-model="contactForm.email" type="email" maxlength="254" autocomplete="email" />
        <label for="contact-phone">전화번호</label>
        <InputText id="contact-phone" v-model="contactForm.phone" type="tel" maxlength="32" autocomplete="tel" />
        <Button label="연락처 저장" type="submit" :loading="busy" />
      </form>
    </section>
  </main>
</template>

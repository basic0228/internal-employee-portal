<script setup>
import { reactive, ref, watch } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { postWithCsrf, errorMessage } from '../api/portal'

const props = defineProps({ visible: Boolean })
const emit = defineEmits(['update:visible', 'created'])
const empty = () => ({ employeeId: '', fullName: '', dateOfBirth: '', username: '', password: '' })
const form = reactive(empty())
const busy = ref(false)
const error = ref('')
watch(() => props.visible, () => { Object.assign(form, empty()); error.value = '' })
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await postWithCsrf('/admin/employees', { ...form, dateOfBirth: form.dateOfBirth || null })
    form.password = ''
    emit('update:visible', false)
    emit('created')
  } catch (failure) { error.value = errorMessage(failure) }
  finally { busy.value = false }
}
</script>

<template>
  <Dialog :visible="visible" @update:visible="emit('update:visible', $event)"
    modal header="직원 계정 생성" :style="{ width: '30rem', maxWidth: '95vw' }">
    <form class="portal-form" @submit.prevent="submit">
      <p>기존 직원의 계정은 같은 사번과 인적사항으로 생성할 수 있습니다.</p>
      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
      <label for="new-id">사번</label>
      <InputText id="new-id" v-model="form.employeeId" required pattern="EMP-[0-9]{3,}" maxlength="32" placeholder="EMP-011" />
      <label for="new-name">성명</label>
      <InputText id="new-name" v-model="form.fullName" required maxlength="100" />
      <label for="new-birth">생년월일 · 미확인 시 비워두세요</label>
      <InputText id="new-birth" v-model="form.dateOfBirth" type="date" />
      <label for="new-username">아이디 · 영문, 숫자, 점, 밑줄, 하이픈</label>
      <InputText id="new-username" v-model="form.username" required pattern="[A-Za-z0-9._-]{3,64}" autocomplete="off" />
      <label for="new-password">초기 비밀번호 · 8자 이상, UTF-8 기준 72바이트 이하</label>
      <InputText id="new-password" v-model="form.password" required type="password" minlength="8" maxlength="72" autocomplete="new-password" />
      <Button type="submit" label="계정 생성" :loading="busy" />
    </form>
  </Dialog>
</template>

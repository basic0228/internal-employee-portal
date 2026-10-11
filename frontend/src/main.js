import { createApp } from 'vue'
import './style.css'
import App from './App.vue'

// Build-time opt-in: preserve the starter and avoid loading portal UI dependencies by default.
async function mount() {
  if (import.meta.env.VITE_PORTAL_UI_ENABLED !== 'true') {
    createApp(App).mount('#app')
    return
  }
  const [{ default: PortalApp }, { createPinia }, { default: PrimeVue }, { default: Aura }] =
    await Promise.all([
      import('./PortalApp.vue'), import('pinia'), import('primevue/config'), import('@primeuix/themes/aura'),
      import('./portal.css'), import('primeicons/primeicons.css'),
    ])
  document.documentElement.classList.add('portal-enabled')
  createApp(PortalApp).use(createPinia())
    .use(PrimeVue, { theme: { preset: Aura, options: { darkModeSelector: false } } })
    .mount('#app')
}
mount()

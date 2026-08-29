import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'
import { LottieExtension } from './editor/LottieExtension'
import LottiePickerModal from './components/LottiePickerModal.vue'
import LottieLibraryView from './views/LottieLibraryView.vue'
import './runtime/lottie-element'

export default definePlugin({
  components: { 'lottie-picker-modal': markRaw(LottiePickerModal) },
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/lottie',
        name: 'LottieLibrary',
        // Keep the manager in the startup module. Halo serves plugin chunks
        // from a separate resource handler on some 2.26 installations; an
        // eager route avoids a missing-MIME chunk preventing the page from
        // opening after the menu is registered.
        component: LottieLibraryView,
        meta: {
          title: '动画管理',
          searchable: true,
          // Use Halo's built-in content group id so the route is rendered in the
          // Console navigation on every supported 2.26 host.
          menu: { name: '动画管理', group: 'content', icon: markRaw(IconPlug), priority: 20 },
        },
      },
    },
  ],
  extensionPoints: { 'default:editor:extension:create': () => [LottieExtension] },
})

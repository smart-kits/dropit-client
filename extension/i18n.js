/**
 * Interface text. English by default; Simplified Chinese when the browser's UI language is Chinese.
 * The manifest's name and description are localized separately, in _locales/.
 */
const EN = {
  errors: {                                   // keyed by the API error code
    INVALID_TOKEN: 'Invalid token — pair again',
    DEVICE_REVOKED: 'This device was removed — pair again',
    SCOPE_INSUFFICIENT: 'This token can only send',
    DEVICE_LIMIT_REACHED: 'Device limit reached — remove a device first',
    PAIRING_CODE_INVALID: 'Invalid pairing code',
    PAIRING_CODE_EXPIRED: 'Pairing code expired — generate a new one',
    RATE_LIMITED: 'Sending too fast',
    SIGNUP_LIMITED: 'Too many accounts were created on this network today. Join an existing account with a pairing code, or try again tomorrow',
    PAIRING_LIMITED: 'Too many wrong pairing codes from this network today. Check the code on the other device and try again tomorrow',
    TURNSTILE_REQUIRED: 'Quite a few accounts were created on this network today. Create this one in the browser at dropit.smart-kits.xyz, then join it here with a pairing code',
    QUOTA_EXCEEDED: 'Storage is full — wait for old items to expire',
  },
  httpFailed: (status) => `Request failed: HTTP ${status}`,
  offline: 'Network unavailable',
  menuSelection: 'Send selected text to dropit',
  menuLink: 'Send this link to dropit',
  menuImage: 'Send image address to dropit',
  menuPage: 'Send this page to dropit',
  sent: (seq) => `Sent #${seq}`,
  alreadySent: (seq) => `Already sent #${seq}`,
  failedTitle: 'dropit: send failed',
  actionTitle: 'Send to dropit',
  noPageUrl: "Can't get this page's address",
  deviceName: (platform) => `Browser · ${platform}`,
  devices: (me) => `${me.plan} · ${me.devices_used}/${me.devices_limit ?? '∞'} devices`,
  html: {
    setupTitle: 'Join with a pairing code',
    codePlaceholder: '6-character pairing code',
    claim: 'Join',
    setupNote: 'Generate it in a dropit client you already use. Valid for 5 minutes.',
    firstTime: 'First time using dropit?',
    create: 'Create a new account',
    page: 'Send this page',
    unpair: 'Sign out on this browser',
    mainNote: 'Right-click selected text, a link or an image to send it directly.',
  },
};

const ZH = {
  errors: {
    INVALID_TOKEN: 'token 无效，请重新配对',
    DEVICE_REVOKED: '设备已被移除，请重新配对',
    SCOPE_INSUFFICIENT: '这个 token 只能投递',
    DEVICE_LIMIT_REACHED: '设备数已达上限，先移除一台',
    PAIRING_CODE_INVALID: '配对码无效',
    PAIRING_CODE_EXPIRED: '配对码已过期，请重新生成',
    RATE_LIMITED: '投递过于频繁',
    SIGNUP_LIMITED: '这个网络今天建的账号太多了。可以用配对码加入已有账号，或者明天再试',
    PAIRING_LIMITED: '这个网络今天输错配对码的次数太多了。核对一下另一台设备上的码，明天再试',
    TURNSTILE_REQUIRED: '这个网络今天建的账号较多。请在浏览器里打开 dropit.smart-kits.xyz 建号，再在这里用配对码加入',
    QUOTA_EXCEEDED: '空间已满，等旧内容过期',
  },
  httpFailed: (status) => `请求失败 HTTP ${status}`,
  offline: '网络不可用',
  menuSelection: '把选中的字投进 dropit',
  menuLink: '把这个链接投进 dropit',
  menuImage: '把图片地址投进 dropit',
  menuPage: '把这个页面投进 dropit',
  sent: (seq) => `已投递 #${seq}`,
  alreadySent: (seq) => `已投过了 #${seq}`,
  failedTitle: 'dropit 投递失败',
  actionTitle: '投进 dropit',
  noPageUrl: '这个页面拿不到地址',
  deviceName: (platform) => `浏览器 · ${platform}`,
  devices: (me) => `${me.plan} · ${me.devices_used}/${me.devices_limit ?? '∞'} 台设备`,
  html: {
    setupTitle: '用配对码加入',
    codePlaceholder: '6 位配对码',
    claim: '加入',
    setupNote: '在你已经在用的 dropit 客户端里生成，5 分钟内有效。',
    firstTime: '第一次用 dropit？',
    create: '创建新账号',
    page: '投当前页面',
    unpair: '在这个浏览器上退出',
    mainNote: '选中文字、链接、图片可以直接右键投递。',
  },
};

export const STRINGS = { en: EN, zh: ZH };
export const lang = String(globalThis.chrome?.i18n?.getUILanguage?.() ?? globalThis.navigator?.language ?? '')
  .toLowerCase().startsWith('zh') ? 'zh' : 'en';
export const t = STRINGS[lang];

const api = require('../../utils/api.js')
const voice = require('../../utils/voice.js')

Page({
  data: {
    characterId: '',
    name: '',
    avatar: '',
    dynasty: '',
    title: '',
    greeting: '',
    sessionId: '',
    messages: [],
    input: '',
    sending: false,
    loaded: false,
    scrollTo: '',
    voiceEnabled: true,
    voiceReady: false,
    recording: false
  },

  onLoad(options) {
    const characterId = options.characterId || ''
    this.setData({
      characterId,
      name: decodeURIComponent(options.name || ''),
      avatar: decodeURIComponent(options.avatar || ''),
      sessionId: 's_' + characterId,
      voiceReady: voice.isVoiceAvailable()
    })
    wx.setNavigationBarTitle({ title: '与' + this.data.name + '对话' })
    this.setupRecognizer()
    this.init()
  },

  onUnload() {
    voice.stopSpeak()
  },

  init() {
    api.getCharacter(this.data.characterId)
      .then(c => {
        this.setData({
          dynasty: c.dynasty,
          title: c.title,
          greeting: c.greeting || '',
          loaded: true
        }, () => this.buildList())
      })
      .catch(() => this.setData({ loaded: true }))

    api.getHistory(this.data.sessionId)
      .then(msgs => {
        this.history = (msgs || []).map(m => ({
          role: m.role,
          content: m.content,
          time: this.formatTime(m.createdAt)
        }))
        this.buildList()
      })
      .catch(() => { this.history = []; this.buildList() })
  },

  history: [],

  /** 组合开场白 + 历史消息 */
  buildList() {
    if (!this.data.loaded) return
    const messages = []
    if (this.data.greeting) {
      messages.push({ role: 'assistant', content: this.data.greeting, time: '', isGreeting: true })
    }
    messages.push(...(this.history || []))
    this.setData({ messages }, () => this.scrollBottom())
  },

  onInput(e) {
    this.setData({ input: e.detail.value })
  },

  onSend() {
    const text = (this.data.input || '').trim()
    if (!text || this.data.sending) return
    this.setData({ input: '' })
    this.sendText(text)
  },

  /** 统一发送入口（键盘输入 / 语音识别共用） */
  sendText(text) {
    if (!text || this.data.sending) return
    this.setData({
      sending: true,
      messages: [...this.data.messages, { role: 'user', content: text, time: this.formatTime(Date.now()) }]
    }, () => this.scrollBottom())

    api.sendChat({
      characterId: this.data.characterId,
      message: text,
      sessionId: this.data.sessionId
    }).then(res => {
      const reply = { role: 'assistant', content: res.reply, time: this.formatTime(res.createdAt) }
      this.setData({
        sending: false,
        messages: [...this.data.messages, reply]
      }, () => this.scrollBottom())
      // 语音播报回复
      if (this.data.voiceEnabled) {
        voice.stopSpeak()
        voice.speak(res.reply)
      }
    }).catch(err => {
      this.setData({
        sending: false,
        messages: [...this.data.messages, { role: 'assistant', content: '（' + err.message + '）', time: this.formatTime(Date.now()), isError: true }]
      }, () => this.scrollBottom())
    })
  },

  /* ============ 语音 ============ */

  setupRecognizer() {
    if (this.recBound) return
    const r = voice.getRecognizer()
    if (!r) return
    this.recBound = true
    r.onStart = () => this.setData({ recording: true })
    r.onStop = (res) => {
      this.setData({ recording: false })
      if (this.recCancelled) { this.recCancelled = false; return }
      const text = ((res && res.result) || '').trim()
      if (text) {
        this.sendText(text)
      } else {
        wx.showToast({ title: '没听清，请再说一次', icon: 'none' })
      }
    }
    r.onError = () => {
      this.setData({ recording: false })
      this.recCancelled = false
      wx.showToast({ title: '语音识别失败', icon: 'none' })
    }
  },

  onVoiceToggle(e) {
    const on = e.detail.value
    this.setData({ voiceEnabled: on })
    if (!on) voice.stopSpeak()
  },

  onRecStart() {
    if (this.data.recording || this.data.sending) return
    if (!this.data.voiceReady) {
      wx.showToast({ title: '语音不可用：请先在公众平台添加「同声传译」插件', icon: 'none' })
      return
    }
    wx.getSetting({
      success: (s) => {
        if (s.authSetting['scope.record']) { this.startRecord(); return }
        wx.authorize({
          scope: 'scope.record',
          success: () => this.startRecord(),
          fail: () => {
            wx.showModal({
              title: '需要麦克风权限',
              content: '请在设置中允许使用麦克风，才能语音提问',
              confirmText: '去设置',
              success: (r) => { if (r.confirm) wx.openSetting() }
            })
          }
        })
      }
    })
  },

  startRecord() {
    const r = voice.getRecognizer()
    if (!r) return
    this.recCancelled = false
    r.start({ duration: 60000, lang: 'zh_CN' })
  },

  onRecEnd() {
    const r = voice.getRecognizer()
    if (r && this.data.recording) r.stop()
  },

  onRecCancel() {
    this.recCancelled = true
    const r = voice.getRecognizer()
    if (r && this.data.recording) r.stop()
  },

  onReplay(e) {
    const content = e.currentTarget.dataset.content
    if (!content) return
    voice.stopSpeak()
    voice.speak(content)
  },

  onClear() {
    voice.stopSpeak()
    wx.showModal({
      title: '清空对话',
      content: '确定要清空与' + this.data.name + '的这段对话吗？',
      confirmColor: '#A03A2B',
      success: (res) => {
        if (!res.confirm) return
        api.clearSession(this.data.sessionId)
          .then(() => {
            this.history = []
            this.buildList()
            wx.showToast({ title: '已清空', icon: 'success' })
          })
          .catch(err => wx.showToast({ title: err.message, icon: 'none' }))
      }
    })
  },

  scrollBottom() {
    const n = this.data.messages.length
    this.setData({ scrollTo: 'msg-' + (n - 1) })
  },

  formatTime(iso) {
    if (!iso) return ''
    const d = new Date(iso)
    if (isNaN(d.getTime())) return ''
    const pad = (x) => (x < 10 ? '0' + x : '' + x)
    return pad(d.getHours()) + ':' + pad(d.getMinutes())
  }
})

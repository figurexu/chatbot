const api = require('../../utils/api.js')

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
    scrollTo: ''
  },

  onLoad(options) {
    const characterId = options.characterId || ''
    this.setData({
      characterId,
      name: decodeURIComponent(options.name || ''),
      avatar: decodeURIComponent(options.avatar || ''),
      sessionId: 's_' + characterId
    })
    wx.setNavigationBarTitle({ title: '与' + this.data.name + '对话' })
    this.init()
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
    this.setData({
      input: '',
      sending: true,
      messages: [...this.data.messages, { role: 'user', content: text, time: this.formatTime(Date.now()) }]
    }, () => this.scrollBottom())

    api.sendChat({
      characterId: this.data.characterId,
      message: text,
      sessionId: this.data.sessionId
    }).then(res => {
      this.setData({
        sending: false,
        messages: [...this.data.messages, { role: 'assistant', content: res.reply, time: this.formatTime(res.createdAt) }]
      }, () => this.scrollBottom())
    }).catch(err => {
      this.setData({
        sending: false,
        messages: [...this.data.messages, { role: 'assistant', content: '（' + err.message + '）', time: this.formatTime(Date.now()), isError: true }]
      }, () => this.scrollBottom())
    })
  },

  onClear() {
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

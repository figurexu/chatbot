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
    recording: false,
    recSlideCancel: false,
    recognizing: false,
    recLiveText: ''
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
      // 语音播报回复（若正在录音则跳过，避免新旧声音打架）
      if (this.data.voiceEnabled && !this._recording) {
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
    const r = voice.getRecorder()
    if (!r) return
    this.recBound = true
    r.onStart(() => {
      this._recording = true
      this.setData({ recording: true, recSlideCancel: false })
    })
    // 流式识别：采集 PCM 帧实时发送（仅流式模式生效）
    r.onFrameRecorded((res) => {
      if (this._streaming && this._streamReady && res && res.frameBuffer) {
        voice.streamSendFrame(res.frameBuffer)
      }
    })
    r.onStop((res) => {
      this._recording = false
      if (this._streaming) {
        // 流式模式：文本由 WebSocket 返回，保留浮层显示实时识别文字
        this.setData({ recSlideCancel: false })
        return
      }
      this.setData({ recording: false, recSlideCancel: false, recLiveText: '' })
      if (this.recCancelled) { this.recCancelled = false; return }
      if (!res || !res.tempFilePath) {
        wx.showToast({ title: '录音失败，请重试', icon: 'none' })
        return
      }
      if (res.duration && res.duration < 800) {
        wx.showToast({ title: '说话时间太短，请重试', icon: 'none' })
        return
      }
      // 识别期间禁止再次录音/重复发送
      this._busy = true
      this.setData({ recognizing: true })
      wx.showLoading({ title: '正在识别…', mask: true })
      voice.recognize(res.tempFilePath, {
        onSuccess: (text) => {
          this._busy = false
          this.setData({ recognizing: false })
          wx.hideLoading()
          this.sendText(text)
        },
        onError: (msg) => {
          this._busy = false
          this.setData({ recognizing: false })
          wx.hideLoading()
          wx.showToast({ title: msg || '没听清，请再说一次', icon: 'none' })
        }
      })
    })
    r.onError(() => {
      this._recording = false
      this._streaming = false
      this.setData({ recording: false, recSlideCancel: false, recLiveText: '' })
      this.recCancelled = false
      wx.showToast({ title: '录音出错，请重试', icon: 'none' })
    })
  },

  onVoiceToggle(e) {
    const on = e.detail.value
    this.setData({ voiceEnabled: on })
    if (!on) voice.stopSpeak()
  },

  onRecStart(e) {
    if (this._recording || this.data.sending) return
    if (this._busy) {
      wx.showToast({ title: '正在识别上一条语音，请稍候', icon: 'none' })
      return
    }
    if (!this.data.voiceReady) {
      wx.showToast({ title: '语音识别服务未配置，请先用文字输入', icon: 'none' })
      return
    }
    // 开始说话前打断正在播放的回复语音
    voice.stopSpeak()
    this.recStartY = e.touches[0].clientY
    this.recWillCancel = false
    wx.getSetting({
      success: (s) => {
        if (s.authSetting['scope.record']) { this.startRecImpl(); return }
        wx.authorize({
          scope: 'scope.record',
          success: () => this.startRecImpl(),
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

  /** 按当前模式开始录音：流式优先，失败自动降级离线 */
  startRecImpl() {
    if (this._streamMode) this.startStreamRecord()
    else this.startRecord()
  },

  /** 流式识别（边说边出字，松手出最终文本） */
  startStreamRecord() {
    const r = voice.getRecorder()
    if (!r) return
    this.recCancelled = false
    this._streaming = true
    this._streamReady = false
    this.setData({ recording: true, recLiveText: '' })
    voice.streamStart({
      onPartial: (text) => {
        if (this._streaming) this.setData({ recLiveText: text })
      },
      onFinal: (text) => {
        this._streaming = false
        this._streamReady = false
        this.setData({ recording: false, recLiveText: '', recSlideCancel: false })
        if (text && text.trim()) {
          this.sendText(text.trim())
        } else {
          wx.showToast({ title: '没听清，请再说一次', icon: 'none' })
        }
      },
      onError: (msg) => {
        this._streaming = false
        this._streamReady = false
        this.setData({ recording: false, recLiveText: '', recSlideCancel: false })
        // 流式不可用 → 降级为离线识别
        this._streamMode = false
        wx.showToast({ title: (msg || '流式识别不可用') + '，本次未发送，请重试', icon: 'none' })
      }
    }).then(() => {
      if (!this._streaming) { voice.streamCancel(); return }
      this._streamReady = true
      r.start({
        duration: 60000,
        format: 'mp3',
        sampleRate: 16000,
        encodeBitRate: 48000,
        frameSize: 10
      })
    }).catch(() => {
      this._streaming = false
      this.setData({ recording: false, recLiveText: '' })
      this._streamMode = false
      wx.showToast({ title: '流式识别不可用，本次未发送，请重试', icon: 'none' })
    })
  },

  startRecord() {
    const r = voice.getRecorder()
    if (!r) return
    this.recCancelled = false
    r.start({
      duration: 60000,
      format: 'mp3',
      sampleRate: 16000,
      encodeBitRate: 48000
    })
  },

  /** 松开发送（上滑时转为取消；流式模式发送结束指令） */
  onRecEnd() {
    const r = voice.getRecorder()
    if (this._streaming) {
      if (this.recWillCancel) {
        this.recCancelled = true
        voice.streamCancel()
        this._streaming = false
        this._streamReady = false
        this.setData({ recording: false, recLiveText: '' })
      } else {
        voice.streamEnd()
      }
      // 停止麦克风采集（onFrameRecorded 停止，文本由 WS 返回）
      if (r && this._recording) r.stop()
      this._recording = false
      return
    }
    if (!this._recording) return
    if (this.recWillCancel) this.recCancelled = true
    this._recording = false
    r.stop()
  },

  /** 上滑取消手势：手指上移超过 60px 即进入"松开取消" */
  onRecMove(e) {
    if (!this._recording || !this.recStartY) return
    const y = e.touches[0].clientY
    const cancel = (this.recStartY - y) > 60
    if (cancel !== this.recWillCancel) {
      this.recWillCancel = cancel
      this.setData({ recSlideCancel: cancel })
    }
  },

  /** 取消录音（系统 touchcancel 或浮层"取消"按钮） */
  onRecCancel() {
    if (this._streaming) {
      voice.streamCancel()
      this._streaming = false
      this._streamReady = false
      this.setData({ recording: false, recLiveText: '', recSlideCancel: false })
      const r = voice.getRecorder()
      if (r && this._recording) r.stop()
      this._recording = false
      return
    }
    this.recCancelled = true
    const r = voice.getRecorder()
    if (this._recording) {
      this._recording = false
      r.stop()
    } else {
      // 未真正开始（如授权弹窗期间抬手），直接收起浮层
      this.setData({ recording: false, recSlideCancel: false })
    }
  },

  /** 浮层"完成"按钮：停止录音并识别发送（流式模式发结束指令） */
  onRecSendTap() {
    const r = voice.getRecorder()
    if (this._streaming) {
      voice.streamEnd()
      if (r && this._recording) r.stop()
      this._recording = false
      return
    }
    if (this._recording) {
      this._recording = false
      r.stop()
    } else {
      this.setData({ recording: false, recSlideCancel: false })
    }
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

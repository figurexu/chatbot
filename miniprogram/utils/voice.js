// 语音能力封装：后端代理方案（无需微信插件，测试号即可用）
// - 录音：wx.getRecorderManager（小程序内置，无需插件）
// - STT：录音文件上传后端 POST /api/voice/stt，由后端调火山引擎豆包语音识别
// - TTS：GET /api/voice/tts?text=... 返回 mp3，InnerAudioContext 播放
// 后端未配置 CHATBOT_VOICE_API_KEY 时接口返回 400，前端提示"语音服务未配置"。

const config = require('../config.js')

let recorder = null
let audioCtx = null
let ttsEndCb = null

// TTS 结果缓存：同一文本二次播放直接复用已下载的临时文件，省一次网络往返
const ttsCache = new Map()
const TTS_CACHE_MAX = 30

/** 语音能力是否可用（后端方案始终声明可用，实际失败以接口返回为准） */
function isVoiceAvailable() {
  return true
}

/** 录音管理器（getRecorderManager，无需插件） */
function getRecorder() {
  if (recorder) return recorder
  recorder = wx.getRecorderManager()
  return recorder
}

/** 上传录音文件并识别成文字 */
function recognize(tempFilePath, callbacks) {
  const cbs = callbacks || {}
  wx.uploadFile({
    url: config.BASE_URL + '/api/voice/stt',
    filePath: tempFilePath,
    name: 'file',
    success: (res) => {
      let data = null
      try {
        data = JSON.parse(res.data)
      } catch (e) { /* ignore */ }
      if (res.statusCode === 200 && data && data.text) {
        if (cbs.onSuccess) cbs.onSuccess(data.text)
      } else if (data && data.error) {
        if (cbs.onError) cbs.onError(data.error)
      } else {
        if (cbs.onError) cbs.onError('识别失败，请重试')
      }
    },
    fail: () => {
      if (cbs.onError) cbs.onError('网络异常，请检查后端服务')
    }
  })
}

/** 文本合成语音并播放；text 过长时截断（后端有长度限制） */
function speak(text, onEnd) {
  const content = String(text || '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 500)
  if (!content) {
    if (onEnd) onEnd()
    return
  }
  ttsEndCb = onEnd

  const cached = ttsCache.get(content)
  if (cached) {
    playFile(cached)
    return
  }

  wx.downloadFile({
    url: config.BASE_URL + '/api/voice/tts?text=' + encodeURIComponent(content),
    success: (res) => {
      if (res.statusCode !== 200 || !res.tempFilePath) {
        fireTtsEnd()
        return
      }
      ttsCache.set(content, res.tempFilePath)
      if (ttsCache.size > TTS_CACHE_MAX) {
        const firstKey = ttsCache.keys().next().value
        ttsCache.delete(firstKey)
      }
      playFile(res.tempFilePath)
    },
    fail: () => fireTtsEnd()
  })
}

function playFile(filePath) {
  stopSpeak()
  audioCtx = audioCtx || wx.createInnerAudioContext()
  audioCtx.src = filePath
  audioCtx.onEnded(() => fireTtsEnd())
  audioCtx.onError(() => fireTtsEnd())
  audioCtx.play()
}

function fireTtsEnd() {
  if (ttsEndCb) {
    const cb = ttsEndCb
    ttsEndCb = null
    cb()
  }
}

/** 停止当前语音播报 */
function stopSpeak() {
  if (audioCtx) {
    audioCtx.stop()
    audioCtx.destroy()
    audioCtx = null
  }
}

module.exports = {
  isVoiceAvailable,
  getRecorder,
  recognize,
  speak,
  stopSpeak
}

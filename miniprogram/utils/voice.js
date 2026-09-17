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

/* ============ 流式识别（边说边出字） ============ */

let streamSocket = null
let streamCbs = null

const WS_OPEN = 1

/**
 * 建立流式识别连接。resolve 在连接建立、配置发送后触发。
 * callbacks: { onPartial(text), onFinal(text), onError(msg) }
 */
function streamStart(callbacks) {
  streamCbs = callbacks || {}
  return new Promise((resolve, reject) => {
    let sock
    try {
      sock = wx.connectSocket({
        url: config.BASE_URL.replace(/^http/, 'ws') + '/api/voice/ws'
      })
    } catch (e) {
      reject(e)
      return
    }
    streamSocket = sock
    sock.onOpen(() => {
      sock.send({ data: JSON.stringify({ type: 'start' }) })
      resolve()
    })
    sock.onMessage((res) => {
      let data = null
      try {
        data = JSON.parse(res.data)
      } catch (e) { /* ignore */ }
      if (!data) return
      if (data.type === 'result' && streamCbs.onPartial) {
        streamCbs.onPartial(data.text)
      } else if (data.type === 'done') {
        if (streamCbs.onFinal) streamCbs.onFinal(data.text || '')
      } else if (data.type === 'error') {
        if (streamCbs.onError) streamCbs.onError(data.text || '识别服务异常')
      }
    })
    sock.onError(() => {
      if (streamCbs.onError) streamCbs.onError('流式识别连接失败')
    })
    sock.onClose(() => {
      streamSocket = null
    })
  })
}

/** 发送一帧 PCM 音频（16k 单声道 16bit） */
function streamSendFrame(buffer) {
  if (streamSocket && streamSocket.readyState === WS_OPEN) {
    streamSocket.send({ data: buffer })
  }
}

/** 结束流式识别（等待最终结果） */
function streamEnd() {
  if (streamSocket && streamSocket.readyState === WS_OPEN) {
    streamSocket.send({ data: JSON.stringify({ type: 'end' }) })
  }
}

/** 取消流式识别并关闭连接 */
function streamCancel() {
  if (streamSocket && streamSocket.readyState === WS_OPEN) {
    streamSocket.send({ data: JSON.stringify({ type: 'cancel' }) })
  }
  try {
    if (streamSocket) streamSocket.close({})
  } catch (e) { /* ignore */ }
  streamSocket = null
}

module.exports = {
  isVoiceAvailable,
  getRecorder,
  recognize,
  speak,
  stopSpeak,
  streamStart,
  streamSendFrame,
  streamEnd,
  streamCancel
}

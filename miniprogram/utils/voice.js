// 语音能力封装：基于微信官方「同声传译」插件 (WechatSI)
// - 语音识别(STT)：getRecordRecognitionManager，按住说话 → 识别成文字
// - 语音合成(TTS)：textToSpeech → 返回音频临时文件 → InnerAudioContext 播放
// 插件未就绪时所有方法安全降级（返回 false / 空操作），不影响文字聊天。

let plugin = null
let recognizer = null
let audioCtx = null
let ttsEndCb = null

function getPlugin() {
  if (plugin !== null) return plugin
  try {
    plugin = requirePlugin('WechatSI')
  } catch (e) {
    plugin = false
  }
  return plugin
}

/** 语音能力是否可用（插件已声明且可加载） */
function isVoiceAvailable() {
  return !!getPlugin()
}

/** 创建（复用）录音识别管理器 */
function getRecognizer() {
  if (recognizer) return recognizer
  const p = getPlugin()
  if (!p) return null
  recognizer = p.getRecordRecognitionManager()
  return recognizer
}

/** 语音合成并播放；text 过长时截断（插件有长度限制） */
function speak(text, onEnd) {
  const p = getPlugin()
  if (!p) {
    if (onEnd) onEnd()
    return
  }
  const content = String(text || '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 120)
  if (!content) {
    if (onEnd) onEnd()
    return
  }
  ttsEndCb = onEnd

  p.textToSpeech({
    lang: 'zh_CN',
    tts: true,
    content,
    success: (res) => {
      if (!res || !res.filename) {
        fireTtsEnd()
        return
      }
      stopSpeak()
      audioCtx = audioCtx || wx.createInnerAudioContext()
      audioCtx.src = res.filename
      audioCtx.onEnded(() => fireTtsEnd())
      audioCtx.onError(() => fireTtsEnd())
      audioCtx.play()
    },
    fail: () => fireTtsEnd()
  })
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
  getRecognizer,
  speak,
  stopSpeak
}

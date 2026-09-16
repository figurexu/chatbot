const config = require('../config.js')

/**
 * 统一的 wx.request Promise 封装
 */
function request(method, path, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: config.BASE_URL + path,
      method: method,
      data: data,
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve(res.data)
        } else {
          const msg = (res.data && (res.data.message || res.data.error)) || ('HTTP ' + res.statusCode)
          reject(new Error(msg))
        }
      },
      fail() {
        reject(new Error('网络请求失败，请确认后端服务已启动（' + config.BASE_URL + '）'))
      }
    })
  })
}

module.exports = {
  /** 历史人物列表 */
  getCharacters: () => request('GET', '/api/characters'),
  /** 人物详情（含开场白） */
  getCharacter: (id) => request('GET', '/api/characters/' + id),
  /** 发送消息 */
  sendChat: (data) => request('POST', '/api/chat', data),
  /** 会话历史 */
  getHistory: (sessionId) => request('GET', '/api/sessions/' + sessionId + '/messages'),
  /** 清空会话 */
  clearSession: (sessionId) => request('DELETE', '/api/sessions/' + sessionId)
}

const api = require('../../utils/api.js')

Page({
  data: {
    characters: [],
    loading: true,
    error: ''
  },

  onLoad() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  load() {
    this.setData({ loading: true, error: '' })
    return api.getCharacters()
      .then(list => {
        this.setData({ characters: list, loading: false })
      })
      .catch(err => {
        this.setData({ loading: false, error: err.message })
      })
  },

  onTapCharacter(e) {
    const { id, name, avatar } = e.currentTarget.dataset
    wx.navigateTo({
      url: '/pages/chat/chat?characterId=' + id +
        '&name=' + encodeURIComponent(name) +
        '&avatar=' + encodeURIComponent(avatar)
    })
  }
})

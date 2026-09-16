// 全局配置
module.exports = {
  // 后端 API 地址
  // 本地调试：在微信开发者工具中勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」后，
  //           可直接使用 http://127.0.0.1:8113（对应 WSL infra 平台部署的 chatbot 服务）
  // 真机 / 生产：替换为你的 HTTPS 域名
  BASE_URL: 'http://127.0.0.1:8113'
}

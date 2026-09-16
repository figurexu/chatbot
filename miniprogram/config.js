// 全局配置
module.exports = {
  // 后端 API 地址
  // 本地调试：在微信开发者工具中勾选「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」后，
  //           可直接使用 http://127.0.0.1:8113（对应 WSL infra 平台部署的 chatbot 服务）
  // 公网访问（当前）：Cloudflare Tunnel（cloudflared）快速隧道，把本地 8113 暴露为 HTTPS 公网地址。
  //           注意：快速隧道地址在 cloudflared 重启后会变化；手机端需在微信中开启「调试」绕过域名校验。
  // 正式上线：替换为你的 HTTPS 域名（需 ICP 备案 + 在 mp.weixin.qq.com 配置 request 合法域名）
  BASE_URL: 'https://florida-pmid-institutes-presentations.trycloudflare.com'
}

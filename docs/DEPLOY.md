# 部署到 WSL infra 平台（GitOps）

> 平台：WSL2 Ubuntu · Docker + k3s · Argo CD + Argo Rollouts · 私有 Registry
> 本应用完全复用平台既有 blog 应用的发布链路：镜像 → Registry → Git 清单 → Argo CD 同步 → 金丝雀发布

## 架构

```
GitHub (figurexu/chatbot)
   │  backend/k8s 清单（Argo CD 监听）
   ▼
Argo CD ──同步──▶ k3s 集群 ──▶ chatbot 命名空间（Rollout 2 副本，金丝雀 25→50→100）
   ▲
镜像 192.168.5.61:5000/chatbot:{tag}（WSL 私有 Registry）
   ▲
Docker 多阶段构建（阿里云 Maven 镜像）
```

## 首次部署

### 0. 前置（一次性）

```bash
# 初始化数据库（MySQL 中间件，pod 名以实际为准）
kubectl exec -n middleware mysql-0 -- mysql -uroot -pmysql-root-2026 < backend/db-init/init-chatbot-db.sql
```

### 1. 构建并推送镜像

```bash
cd backend
docker build --network=host -t 192.168.5.61:5000/chatbot:0.1.0 .
docker push 192.168.5.61:5000/chatbot:0.1.0
# 确认（绕过代理）
curl --noproxy '*' http://192.168.5.61:5000/v2/chatbot/tags/list
```

> 注意：WSL IP 为 DHCP 动态分配，若重启后变化，所有 `192.168.5.x:5000` 引用、k3s registries.yaml 均需同步更新（见平台文档第五节）。

### 2. 提交清单，Argo CD 自动同步

```bash
git add -A && git commit -m "deploy: chatbot 0.1.0"
git push origin main    # 内网环境先清代理：env -u http_proxy -u https_proxy -u all_proxy git push origin main
```

Argo CD 的 `backend/argocd/application-chatbot.yaml` 监听 GitHub 仓库 `backend/k8s` 路径，同步后约 30~60s 生效。首次需要把 GitHub 仓库加入 Argo CD 仓库列表（一次性）：

```bash
kubectl -n argocd create secret generic chatbot-repo \
  --from-literal=type=git \
  --from-literal=url=https://github.com/figurexu/chatbot.git \
  --dry-run=client -o yaml | \
kubectl apply -f - --dry-run=client \
  && kubectl -n argocd label secret chatbot-repo argocd.argoproj.io/secret-type=repository
kubectl -n argocd apply -f backend/argocd/application-chatbot.yaml
```

### 3. 金丝雀发布

```bash
kubectl argo rollouts get rollout chatbot -n chatbot
kubectl argo rollouts promote chatbot -n chatbot     # 25% → 暂停 → 50% → 暂停 → 100%
kubectl argo rollouts status chatbot -n chatbot
```

### 4. 端口转发（Windows 访问）

```bash
# /usr/local/bin/chatbot-pf.sh
export KUBECONFIG=/home/figur/.kube/config
kubectl port-forward svc/chatbot -n chatbot 8113:80 --address 127.0.0.1 &
wait

# /etc/systemd/system/chatbot-portforward.service（参照 blog-portforward）
sudo systemctl daemon-reload
sudo systemctl enable --now chatbot-portforward
```

验证：`curl http://127.0.0.1:8113/healthz`

## 日常发布新版本（5 步）

```bash
# 1. 改代码
# 2. 构建推送新镜像
cd backend && docker build -t 192.168.5.61:5000/chatbot:X.Y.Z . && docker push 192.168.5.61:5000/chatbot:X.Y.Z
# 3. 更新 backend/k8s/03-rollout.yaml 的 image tag 与 APP_VERSION
# 4. 提交推送 GitHub → Argo CD 自动同步
git add -A && git commit -m "release: chatbot X.Y.Z" && git push origin main
# 5. 金丝雀 promote（可选，等自动同步后操作）
kubectl argo rollouts promote chatbot -n chatbot
```

## 接入真实 LLM（k8s 环境）

1. 编辑 `backend/k8s/01-configmap.yaml`：`CHATBOT_LLM_PROVIDER: "openai"`，并按需改 BASE_URL
2. 编辑 `backend/k8s/02-secret.yaml`：填入 `CHATBOT_LLM_API_KEY` 与 `CHATBOT_LLM_MODEL`
3. 提交推送 → Argo CD 同步；改过 ConfigMap/Secret 后 pod 不会自动重启，需滚动重建：

```bash
kubectl -n chatbot rollout restart rollout/chatbot
```

> ⚠️ Secret 明文入仓仅限本地演示环境；生产建议 Sealed Secrets / External Secrets / Vault。

## 故障速查

| 现象 | 处理 |
|------|------|
| Pod ErrImagePull | WSL 重启后 IP 变了：`hostname -I` 查新 IP，改镜像 tag 与 registries.yaml |
| 页面打不开但 Pod 正常 | 端口转发断了：`sudo systemctl restart chatbot-portforward` |
| Argo CD 不同步 | `kubectl -n argocd get application chatbot` 看状态，手动 `kubectl -n argocd patch application chatbot --type merge -p '{"operation":{"sync":{}}}'` |
| 数据库连不上 | 确认 `mysql.middleware.svc` 可达、`chatbot` 库与 appuser 授权已建（步骤 0） |
| LLM 返回 502 | 检查 Secret 中 API Key/模型名、ConfigMap 中 BASE_URL，Pod 日志 `kubectl logs -n chatbot deploy/chatbot -f` |

# 史海对话 · History Character Chatbot

与历史人物对话的聊天应用：选择一位历史人物（张居正、诸葛亮、苏轼、李白、武则天、王阳明……），以第一人称人设与 TA 对话。

```
┌─────────────────┐        ┌──────────────────────┐        ┌─────────────────┐
│  微信小程序客户端  │  HTTP  │  Spring Boot (Java 21) │  HTTP  │  LLM Provider    │
│ 人物选择 / 聊天界面 │ ─────▶ │ 人物管理 / 会话 / 聊天  │ ─────▶ │ mock(内置) /      │
└─────────────────┘        │  MySQL 持久化 (JPA+Flyway)│        │ OpenAI 兼容接口    │
                           └──────────────────────┘        └─────────────────┘
                                    │ k8s (k3s) 部署
                              WSL infra：Docker 镜像 → 私有 Registry → Argo CD GitOps
```

## 功能

- 6 位历史人物，各自独立的时代背景、性格人设与开场白
- 多轮对话，服务端持久化会话历史，可随时清空重来
- 后端 LLM 可插拔：默认内置 Mock 演示回复（无需 API Key），配置环境变量即可接入豆包方舟 / DeepSeek / OpenAI 等任意 OpenAI 兼容接口
- 微信小程序：水墨宣纸风格，人物选择页 + 聊天页

## 目录结构

```
chatbot/
├── backend/                    # Java Spring Boot 后端
│   ├── src/main/java/com/chatbot/
│   │   ├── controller/         # REST API（人物 / 聊天 / 会话）
│   │   ├── service/            # 业务逻辑
│   │   ├── llm/                # LLM 抽象层（mock / openai 兼容）
│   │   ├── entity/ repository/ # JPA 实体与仓储
│   │   └── config/             # CORS / LLM 装配
│   ├── src/main/resources/
│   │   ├── db/migration/       # Flyway 建表 + 人物种子数据
│   │   └── application.yml
│   ├── k8s/                    # 部署清单（namespace/configmap/secret/rollout/service）
│   ├── argocd/                 # Argo CD Application 定义
│   ├── db-init/                # 数据库初始化脚本
│   ├── Dockerfile  pom.xml  settings.xml
├── miniprogram/                # 微信小程序前端
│   ├── pages/index/            # 人物选择页
│   ├── pages/chat/             # 聊天页
│   ├── images/avatars/         # 人物头像（AI 生成）
│   └── config.js               # 后端地址配置
└── docs/
    ├── API.md                  # 接口文档
    └── DEPLOY.md               # 部署到 WSL infra 平台全流程
```

## 快速开始

### 1. 启动后端（任选）

**方式 A：直接本地运行**（需 Java 21 + Maven + MySQL）

```bash
cd backend
mvn spring-boot:run
```

**方式 B：Docker 运行**

```bash
cd backend
docker build -t chatbot:local .
docker run -d -p 8080:8080 \
  -e CHATBOT_DB_HOST=127.0.0.1 -e CHATBOT_DB_PORT=3306 \
  -e CHATBOT_DB_USER=appuser -e CHATBOT_DB_PASS=app-pass-2026 \
  chatbot:local
```

默认使用 `mock` LLM，无需任何 Key；验证：`curl http://127.0.0.1:8080/healthz`

### 2. 接入真实 LLM（可选）

后端已内置 OpenAI 兼容客户端（豆包方舟、DeepSeek、OpenAI 等通用）。设置环境变量：

```bash
export CHATBOT_LLM_PROVIDER=openai
export CHATBOT_LLM_API_KEY=你的Key
export CHATBOT_LLM_MODEL=你的模型名   # 如 doubao-seed-1-6-250615 / deepseek-chat
export CHATBOT_LLM_BASE_URL=https://ark.cn-beijing.volces.com/api/v3   # 按服务商修改
```

### 3. 运行小程序

1. 用**微信开发者工具**导入 `miniprogram/` 目录
2. 后端地址在 `miniprogram/config.js` 的 `BASE_URL` 配置：
   - 本地调试：`http://127.0.0.1:8080`（需在开发者工具「详情 → 本地设置」勾选*不校验合法域名*）
   - infra 平台部署：`http://127.0.0.1:8113`（见部署文档）
   - 真机/生产：替换为你的 HTTPS 域名（需在小程序后台配置 request 合法域名）
3. 编译预览即可

### 4. 部署到 WSL infra 平台（GitOps）

完整流程见 [docs/DEPLOY.md](docs/DEPLOY.md)。概要：

```bash
# 构建镜像并推送私有 Registry
cd backend
docker build -t 192.168.5.61:5000/chatbot:0.1.0 .
docker push 192.168.5.61:5000/chatbot:0.1.0

# k8s 清单提交到 GitHub → Argo CD 自动同步 → 金丝雀发布
git add -A && git commit -m "deploy: chatbot 0.1.0"
git push origin main
```

## 主要 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/characters` | 历史人物列表 |
| GET | `/api/characters/{id}` | 人物详情（含开场白） |
| POST | `/api/chat` | 发送消息，body: `{characterId, message, sessionId?}` |
| GET | `/api/sessions/{sessionId}/messages` | 会话历史 |
| DELETE | `/api/sessions/{sessionId}` | 清空会话 |
| GET | `/healthz` | 健康检查 |

完整字段说明见 [docs/API.md](docs/API.md)。

## 技术栈

- 后端：Java 21 · Spring Boot 3.3.5 · Spring Data JPA · Flyway · MySQL
- 前端：微信小程序原生（WXML/WXSS/JS）
- 部署：WSL2 Ubuntu + Docker + k3s + Argo CD（GitOps + 金丝雀发布）+ 私有 Registry
- LLM：可插拔接口，内置 mock，兼容 OpenAI 协议（豆包方舟 / DeepSeek 等）

## 新增历史人物

1. 生成头像放入 `miniprogram/images/avatars/`
2. 在 `backend/src/main/resources/db/migration/` 新增 `V3__add_character.sql` 插入人物记录（含 system_prompt 人设）
3. 重新构建镜像 → 推 Registry → 更新 rollout 镜像 tag → push 触发 Argo CD 同步

## 后续方向

- iOS / Android 客户端：后端 API 与前端无关，直接复用
- SSE 流式输出、语音对话
- 接入 Dify 工作流做更复杂的角色编排
- 用户系统、多会话管理、人物形象动态生成

# 接口文档

Base URL：`http://127.0.0.1:8113`（infra 平台）或本地 `http://127.0.0.1:8080`

## GET /api/characters

返回全部启用的历史人物列表，按 sort_order 排序。

```json
[
  {
    "id": "zhangjuzheng",
    "name": "张居正",
    "dynasty": "明朝·万历",
    "title": "内阁首辅·太师",
    "tagline": "天下之事，不难于立法，而难于法之必行",
    "avatar": "zhangjuzheng.png",
    "greeting": "老夫张居正，字叔大，江陵人也……"
  }
]
```

## GET /api/characters/{id}

单个人物详情（同上结构）。

错误：`404 {"message": "人物不存在: xxx"}`

## POST /api/chat

发送消息并获取回复（同步，非流式）。

请求：

```json
{
  "characterId": "zhangjuzheng",
  "message": "先生对一条鞭法有何见解？",
  "sessionId": "s_zhangjuzheng"
}
```

- `sessionId` 可选：不传则服务端自动生成；固定传同一值可维持多轮上下文
- `message` 必填，≤2000 字

响应：

```json
{
  "sessionId": "s_zhangjuzheng",
  "characterId": "zhangjuzheng",
  "reply": "一条鞭法者……",
  "createdAt": "2026-09-16T14:20:00Z"
}
```

错误：`400` 参数校验失败；`404` 人物不存在；`502` LLM 服务不可用。

## GET /api/sessions/{sessionId}/messages

返回会话历史（按时间升序）：

```json
[
  { "role": "user", "content": "……", "createdAt": "2026-09-16T14:19:00Z" },
  { "role": "assistant", "content": "……", "createdAt": "2026-09-16T14:20:00Z" }
]
```

## DELETE /api/sessions/{sessionId}

清空会话历史，返回 204。

## GET /healthz

健康检查（k8s 探针）：

```json
{ "status": "UP", "version": "0.1.0", "llm": "mock" }
```

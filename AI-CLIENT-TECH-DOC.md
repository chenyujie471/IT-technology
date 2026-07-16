# 多模态 AI 客户端 — 技术设计文档

> 适用范围：文字对话 · 图像生成 · 视频生成（文生视频 / 图生视频 / 视频参考）  
> API 网关：`https://ai-api.kkidc.com`  
> 鉴权：统一 `Authorization: Bearer {token}`（单一 Token，无需多厂商签名）

---

## 一、API 总览

| 功能 | 路径 | 方法 | 格式 |
|------|------|------|------|
| 文字对话 (Claude) | `/v1/messages` | POST | Anthropic 官方格式 |
| 图像生成 | `/v1/images/generations` | POST | OpenAI 兼容格式 |
| 提交视频任务 | `/api/v3/contents/generations/tasks` | POST | 火山豆包格式 |
| 查询视频任务 | `/api/v3/contents/generations/tasks/{task_id}` | GET | 火山豆包格式 |

---

## 二、各接口详细规范

### 2.1 文字对话 — Claude

**端点**
```
POST https://ai-api.kkidc.com/v1/messages
```

**请求头**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**可用模型**
```
claude-haiku-4-5-20251001
claude-sonnet-4-6
claude-opus-4-6
claude-opus-4-7
```

**请求体**
```json
{
  "model": "claude-sonnet-4-6",
  "messages": [
    { "role": "user", "content": "Hello" }
  ],
  "max_tokens": 500
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| model | string | ✅ | 模型 ID |
| messages | array | ✅ | 对话历史，`role` 可为 `user` / `assistant` / `system` |
| max_tokens | integer | — | 最大输出 Token 数 |

**响应** — Anthropic 标准格式，支持流式输出（SSE）

---

### 2.2 图像生成 — gpt-image-2

**端点**
```
POST https://ai-api.kkidc.com/v1/images/generations
```

> ⚠️ 生成较慢，超时建议设 **600 秒**；优先使用**流式请求**（`"stream": true`）；不要直接用 Apifox 调试（会主动断开）

**请求头**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**可用模型**
```
gpt-image-2
```

**请求体**
```json
{
  "model": "gpt-image-2",
  "prompt": "masterpiece, best quality, ultra detailed, anime style...",
  "size": "1024x1024",
  "n": 1,
  "quality": "high",
  "response_format": "b64_json",
  "stream": true
}
```

| 字段 | 类型 | 必填 | 可选值 |
|------|------|------|--------|
| model | string | ✅ | `gpt-image-2` |
| prompt | string | ✅ | 图像描述 |
| size | string | ✅ | `auto` / `1024x1024` / `1536x1024` / `1024x1536` / `2048x2048`(2K) / `2048x1152`(2K) / `3840x2160`(4K) / `2160x3840`(4K) |
| n | integer | ✅ | 仅支持 `1` |
| quality | string | ✅ | `auto` / `low` / `medium` / `high` |
| response_format | string | ✅ | 固定 `b64_json` |
| stream | boolean | — | 推荐 `true` |

**响应**
```json
{
  "data": [
    {
      "url": "",
      "b64_json": "base64图片数据",
      "revised_prompt": "系统优化后的提示词"
    }
  ],
  "created": 1776824208
}
```

> `b64_json` 直接是 base64 字符串，前端用 `data:image/png;base64,{b64_json}` 显示

---

### 2.3 视频生成 — doubao-seedance-2-0

#### 2.3.1 提交视频生成任务

**端点**
```
POST https://ai-api.kkidc.com/api/v3/contents/generations/tasks
```

**请求头**
```
Authorization: Bearer {token}
Content-Type: application/json
```

**可用模型**
```
doubao-seedance-2-0-260128
```

**请求体**
```json
{
  "model": "doubao-seedance-2-0-260128",
  "prompt": "写实风格，晴朗的蓝天之下，一大片白色的雏菊花田，镜头逐渐拉近...",
  "generate_audio": true,
  "ratio": "16:9",
  "duration": 5,
  "resolution": "480p",
  "watermark": false
}
```

| 字段 | 类型 | 必填 | 可选值 / 说明 |
|------|------|------|--------------|
| model | string | ✅ | `doubao-seedance-2-0-260128` |
| prompt | string | ✅ | 视频描述，对话内容建议放双引号内以优化音频 |
| generate_audio | boolean | ✅ | `true`=含音频，`false`=静音，默认 `true` |
| ratio | string | ✅ | `16:9` / `4:3` / `1:1` / `3:4` / `9:16` / `21:9` / `adaptive` |
| duration | integer | ✅ | 时长（秒），默认 5，范围 4~15 |
| resolution | string | ✅ | `480p` / `720p` 等 |
| watermark | boolean | ✅ | `false`=无水印（默认），`true`=含水印 |

**响应（任务已提交）**
```json
{
  "id": "cgt-20260227153643-mx6c7",
  "task_id": "cgt-20260227153643-mx6c7",
  "object": "video",
  "model": "doubao-seedance-2-0-260128",
  "status": "",
  "progress": 0,
  "created_at": 1772177804
}
```

> 拿到 `task_id` 后轮询查询接口

---

#### 2.3.2 查询视频任务状态（轮询）

**端点**
```
GET https://ai-api.kkidc.com/api/v3/contents/generations/tasks/{task_id}
```

**请求头**
```
Authorization: Bearer {token}
```

**任务状态值**

| status（接口层） | 含义 |
|----------------|------|
| `NOT_START` | 未开始 |
| `SUBMITTED` | 已提交 |
| `QUEUED` | 排队中 |
| `IN_PROGRESS` | 处理中 |
| `SUCCESS` | 成功 |
| `FAILURE` | 失败 |
| `UNKNOWN` | 未知 |

**成功响应示例**
```json
{
  "code": "success",
  "message": "",
  "data": {
    "task_id": "cgt-20260227150701-bwgfp",
    "action": "generate",
    "status": "SUCCESS",
    "fail_reason": "",
    "submit_time": 1772176021,
    "start_time": 1772176028,
    "finish_time": 1772176073,
    "progress": "100%",
    "data": {
      "id": "cgt-20260227150701-bwgfp",
      "seed": 22698,
      "model": "doubao-seedance-2-0-260128",
      "ratio": "16:9",
      "status": "succeeded",
      "content": {
        "video_url": "https://ark-content-generation-cn-beijing.tos-cn-beijing.volces.com/..."
      },
      "duration": 5,
      "resolution": "720p",
      "generate_audio": true,
      "framespersecond": 24
    }
  }
}
```

> 视频地址在 `data.data.content.video_url`

---

## 三、前端实现方案

### 3.1 技术选型

| 技术 | 版本 | 用途 |
|------|------|------|
| Next.js | 14+ | 框架（App Router + API Routes 作代理层） |
| TypeScript | 5+ | 类型安全 |
| TailwindCSS | 3+ | 样式 |
| Zustand | 4+ | 全局状态（模型配置、对话、生成任务） |
| shadcn/ui | latest | UI 组件库 |
| react-dropzone | — | 拖拽上传参考图/视频 |
| react-player | — | 视频预览播放 |

### 3.2 目录结构

```
ai-client/
├── app/
│   ├── chat/page.tsx              # 文字对话页
│   ├── image/page.tsx             # 图像生成页
│   ├── video/page.tsx             # 视频生成页
│   └── api/
│       ├── chat/route.ts          # → POST /v1/messages
│       ├── image/route.ts         # → POST /v1/images/generations
│       └── video/
│           ├── submit/route.ts    # → POST /api/v3/contents/generations/tasks
│           └── poll/route.ts      # → GET  /api/v3/contents/generations/tasks/{id}
├── lib/
│   ├── api.ts                     # 统一请求封装（带 Bearer Token）
│   ├── types.ts                   # 全局类型定义
│   └── model-config.ts            # 模型/Token 本地加密存储
├── components/
│   ├── chat/ChatWindow.tsx
│   ├── image/ImageGenerator.tsx
│   └── video/VideoGenerator.tsx
└── store/
    ├── config.ts                  # API Token & 模型配置
    └── tasks.ts                   # 视频任务状态
```

---

## 四、核心代码实现

### 4.1 统一请求客户端

```typescript
// lib/api.ts

const BASE = 'https://ai-api.kkidc.com'

function getToken(): string {
  return localStorage.getItem('ai_token') ?? ''
}

function headers(extra?: Record<string, string>) {
  return {
    'Authorization': `Bearer ${getToken()}`,
    'Content-Type': 'application/json',
    ...extra,
  }
}

export const api = { BASE, headers }
```

### 4.2 文字对话 — 流式 SSE

```typescript
// lib/chat.ts

export async function* streamChat(
  model: string,
  messages: { role: string; content: string }[],
  maxTokens = 2000,
): AsyncGenerator<string> {
  const res = await fetch('https://ai-api.kkidc.com/v1/messages', {
    method: 'POST',
    headers: api.headers(),
    body: JSON.stringify({ model, messages, max_tokens: maxTokens, stream: true }),
  })

  const reader = res.body!.getReader()
  const decoder = new TextDecoder()

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    const lines = decoder.decode(value).split('\n').filter(l => l.startsWith('data: '))
    for (const line of lines) {
      const data = line.slice(6)
      if (data === '[DONE]') return
      try {
        const json = JSON.parse(data)
        const text = json.delta?.text ?? json.content?.[0]?.text ?? ''
        if (text) yield text
      } catch {}
    }
  }
}
```

### 4.3 图像生成 — 流式 + Base64 解码

```typescript
// lib/image.ts

export interface ImageParams {
  prompt: string
  size?: string
  quality?: 'auto' | 'low' | 'medium' | 'high'
}

export async function generateImage(params: ImageParams): Promise<string> {
  const res = await fetch('https://ai-api.kkidc.com/v1/images/generations', {
    method: 'POST',
    headers: api.headers(),
    // 设置超时 600s（在 Next.js API Route 中代理，避免前端直连超时）
    body: JSON.stringify({
      model: 'gpt-image-2',
      prompt: params.prompt,
      size: params.size ?? '1024x1024',
      n: 1,
      quality: params.quality ?? 'high',
      response_format: 'b64_json',
      stream: true,
    }),
  })

  const json = await res.json()
  const b64 = json.data[0].b64_json
  return `data:image/png;base64,${b64}`
}
```

### 4.4 视频生成 — 提交 + 轮询

```typescript
// lib/video.ts

export interface VideoParams {
  prompt: string
  ratio?: string
  duration?: number
  resolution?: string
  generateAudio?: boolean
}

export async function submitVideoTask(params: VideoParams): Promise<string> {
  const res = await fetch(
    'https://ai-api.kkidc.com/api/v3/contents/generations/tasks',
    {
      method: 'POST',
      headers: api.headers(),
      body: JSON.stringify({
        model: 'doubao-seedance-2-0-260128',
        prompt: params.prompt,
        generate_audio: params.generateAudio ?? true,
        ratio: params.ratio ?? '16:9',
        duration: params.duration ?? 5,
        resolution: params.resolution ?? '720p',
        watermark: false,
      }),
    }
  )
  const data = await res.json()
  return data.task_id
}

export type TaskStatus = 'NOT_START' | 'SUBMITTED' | 'QUEUED' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILURE' | 'UNKNOWN'

export interface VideoTaskResult {
  status: TaskStatus
  progress: string
  videoUrl?: string
  failReason?: string
}

export async function pollVideoTask(taskId: string): Promise<VideoTaskResult> {
  const res = await fetch(
    `https://ai-api.kkidc.com/api/v3/contents/generations/tasks/${taskId}`,
    { headers: api.headers() }
  )
  const json = await res.json()
  const d = json.data

  return {
    status: d.status as TaskStatus,
    progress: d.progress ?? '0%',
    videoUrl: d.data?.content?.video_url,
    failReason: d.fail_reason,
  }
}

// 前端轮询驱动（每 3 秒）
export async function waitForVideo(
  taskId: string,
  onProgress: (progress: string) => void,
  timeoutMs = 5 * 60 * 1000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs

  while (Date.now() < deadline) {
    const result = await pollVideoTask(taskId)
    onProgress(result.progress)

    if (result.status === 'SUCCESS' && result.videoUrl) {
      return result.videoUrl
    }
    if (result.status === 'FAILURE') {
      throw new Error(`视频生成失败: ${result.failReason}`)
    }

    await new Promise(r => setTimeout(r, 3000))
  }
  throw new Error('视频生成超时（>5 分钟）')
}
```

### 4.5 Next.js 代理层（解决超时 & 跨域）

```typescript
// app/api/image/route.ts
// 前端请求 /api/image，后端代理转发，避免 600s 超时在浏览器层被截断

import { NextRequest } from 'next/server'

export const maxDuration = 600  // Vercel 需要 Pro 以上；自部署无限制

export async function POST(req: NextRequest) {
  const body = await req.json()
  const token = req.headers.get('x-ai-token') ?? ''

  const upstream = await fetch('https://ai-api.kkidc.com/v1/images/generations', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  return new Response(upstream.body, {
    status: upstream.status,
    headers: { 'Content-Type': 'application/json' },
  })
}
```

---

## 五、UI 页面设计

### 5.1 Token 配置入口（首次使用 / 设置页）

```
┌─────────────────────────────────────────┐
│  API 配置                                │
│  ┌──────────────────────────────────┐   │
│  │ Bearer Token  __________________ │   │
│  └──────────────────────────────────┘   │
│  [测试连接]  ← 发送一条 Hello 验证      │
└─────────────────────────────────────────┘
```

### 5.2 视频生成页

```
┌───────────────────────────────────────────────────────┐
│  模型  [doubao-seedance-2-0-260128 ▼]                  │
│                                                        │
│  Prompt ─────────────────────────────────────────────  │
│  ┌───────────────────────────────────────────────────┐│
│  │ 写实风格，晴朗的蓝天之下，一大片白色的雏菊花田...  ││
│  └───────────────────────────────────────────────────┘│
│                                                        │
│  比例 [16:9][4:3][1:1][9:16][21:9]                    │
│  时长 [4s][5s][10s][15s]   分辨率 [480p][720p]         │
│  ☑ 生成音频   ☐ 添加水印                               │
│                                                        │
│  [生成视频]                                            │
│                                                        │
│  ── 任务状态 ────────────────────────────────────────  │
│  task_id: cgt-20260227153643-mx6c7                     │
│  ████████████░░░░  75%  IN_PROGRESS                    │
│                                                        │
│  [完成后视频播放器显示在此]                             │
└───────────────────────────────────────────────────────┘
```

### 5.3 图像生成页

```
┌───────────────────────────────────────────────────────┐
│  模型  [gpt-image-2 ▼]                                 │
│                                                        │
│  Prompt ─────────────────────────────────────────────  │
│  ┌───────────────────────────────────────────────────┐│
│  │ masterpiece, best quality, ultra detailed...      ││
│  └───────────────────────────────────────────────────┘│
│                                                        │
│  尺寸 [auto][1024x1024][1536x1024][1024x1536]          │
│       [2048x2048(2K)][3840x2160(4K)]                   │
│  质量 [auto][low][medium][high]                        │
│                                                        │
│  [生成图像]  ← 请耐心等待，约 30~120s                  │
│                                                        │
│  ── 结果 ────────────────────────────────────────────  │
│  ┌────────────────────┐                                │
│  │      生成图片       │  [下载] [复制 base64]          │
│  └────────────────────┘                                │
└───────────────────────────────────────────────────────┘
```

---

## 六、实现路线图

### Phase 1 — 基础框架（3~5 天）
- [ ] Next.js 14 初始化 + 全局 Token 配置（本地 localStorage 存储）
- [ ] Claude 文字对话页（流式输出 + 多轮对话）
- [ ] 模型切换（claude-haiku / claude-sonnet / claude-opus）

### Phase 2 — 图像生成（2~3 天）
- [ ] gpt-image-2 文生图（流式转发 + base64 展示）
- [ ] 尺寸 / 质量参数面板
- [ ] 图像结果画廊 & 一键下载

### Phase 3 — 视频生成（3~5 天）
- [ ] doubao-seedance-2-0 文生视频
- [ ] 任务提交 → 轮询状态 → 进度条展示
- [ ] 完成后视频播放 & 下载链接
- [ ] 失败重试机制

### Phase 4 — 完善体验（2~3 天）
- [ ] 历史记录（对话 / 图片 / 视频）
- [ ] 图生视频（如 API 支持 image 参数）
- [ ] 批量对比模式（同 Prompt 多模型横向对比）

---

## 七、快速启动

```bash
# 初始化
npx create-next-app@latest ai-client --typescript --tailwind --app
cd ai-client

# 安装依赖
npm install zustand @tanstack/react-query react-dropzone react-player
npx shadcn@latest init
npx shadcn@latest add button input textarea select dialog tabs progress badge

# 启动开发
npm run dev
```

---

## 八、错误处理规范

| HTTP 状态码 | 含义 | 处理方式 |
|------------|------|---------|
| 200 | 成功 | 正常处理 |
| 400 | 请求参数错误 | 展示具体错误信息 |
| 429 | 限流 | 提示"请求过于频繁，请稍后重试" + 指数退避重试 |
| 视频 FAILURE | 生成失败 | 展示 `fail_reason`，提供重试按钮 |
| 超时（图像）| 600s 内无响应 | 提示"生成超时，建议降低分辨率后重试" |

---

*文档版本: v2.0 | 2026-07-13 | 基于公司内部 API 文档更新*

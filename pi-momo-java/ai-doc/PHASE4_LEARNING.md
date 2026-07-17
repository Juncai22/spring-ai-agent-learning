# 阶段四：pi-ai-providers 学习文档

> 本文档带你系统性地学习 pi-ai-providers 模块，即 AI 模型提供商的具体实现。这个模块实现了与各大 LLM API 的对接，包括消息格式转换、HTTP 调用、SSE 流解析、重试策略等。建议边读本文档边打开对应源码文件对照学习。

---

## 一、模块概览

**pi-ai-providers** 依赖 pi-ai-core（使用其类型系统和 ApiProvider 接口），实现了所有内置的 AI 提供商。

### 核心功能

- **BaseProvider 抽象基类**：提供者模式的核心骨架，封装通用 HTTP 调用逻辑
- **MessageTransformer**：跨 Provider 的消息格式转换
- **RetryPolicy**：智能重试策略
- **SseParser**：SSE 流解析器
- **多平台实现**：Anthropic、OpenAI、Google、Mistral、AWS Bedrock

### 包结构

```
pi-ai-providers/
  ├── common/                     ← 公共基础设施
  │   ├── BaseProvider.java       ← 抽象基类
  │   ├── MessageTransformer.java ← 消息转换
  │   ├── RetryPolicy.java        ← 重试策略
  │   └── SseParser.java          ← SSE 解析
  ├── builtin/                    ← 内置提供者注册
  │   └── BuiltInProviders.java
  ├── anthropic/                  ← Anthropic Claude
  │   └── AnthropicProvider.java
  ├── openai/                     ← OpenAI
  │   ├── OpenAIResponsesProvider.java
  │   ├── OpenAICompletionsProvider.java
  │   ├── OpenAICodexResponsesProvider.java
  │   └── AzureOpenAIResponsesProvider.java
  ├── google/                     ← Google
  │   ├── GoogleGeminiProvider.java
  │   ├── GoogleGeminiCliProvider.java
  │   ├── GoogleVertexProvider.java
  │   └── GoogleShared.java
  ├── mistral/                    ← Mistral AI
  │   └── MistralProvider.java
  └── bedrock/                    ← AWS Bedrock
      └── BedrockProvider.java
```

---

## 二、公共基础设施（common/ 包）— 4 个文件

### 2.1 BaseProvider.java — 抽象基类

**文件：** `common/BaseProvider.java`

#### 核心架构：提供者模式（Provider Pattern）

提供者模式将 API 调用中的不变部分与可变部分分离：

```
不变部分（BaseProvider 提供）
├── 共享 HttpClient 实例（连接池复用）
├── HTTP POST 请求构建（buildPostRequest）
├── 带重试的请求发送（sendWithRetry）
├── SSE 流解析
├── JSON 序列化/反序列化
├── 错误处理
├── 取消信号处理
└── 初始输出对象创建（createInitialOutput）

可变部分（子类实现）
├── api() 返回 API 标识
├── 消息格式转换（内部 → 目标 API 格式）
├── SSE 事件映射（目标 API → 内部事件模型）
├── 认证方式（API Key / OAuth / 自定义头）
├── 端点 URL 构建
└── 特殊参数处理（如 Anthropic 的 anthropic-version 头）
```

#### 流式调用完整流程

```
Provider.stream(model, context, options)
  │
  ├─ 1. 创建 AssistantMessageEventStream
  │
  ├─ 2. 异步执行 HTTP 请求
  │     │
  │     ├─ 2.1 构建请求体 JSON（子类实现消息转换）
  │     ├─ 2.2 构建 HTTP 请求（buildPostRequest）
  │     ├─ 2.3 发送请求（sendWithRetry）
  │     │     ├─ 发送 HTTP POST
  │     │     ├─ 检查响应状态码
  │     │     ├─ 2xx → 继续
  │     │     ├─ 429/5xx → 判断是否重试
  │     │     │     ├─ 可重试 → 等待指数退避后重试
  │     │     │     └─ 不可重试 → 抛出异常
  │     │     └─ 其他 → 抛出异常
  │     │
  │     ├─ 2.4 解析 SSE 流
  │     │     ├─ 逐行读取 InputStream
  │     │     ├─ 解析 SSE 格式（event: xxx\ndata: xxx\n\n）
  │     │     ├─ 将 SSE 事件映射为内部 AssistantMessageEvent
  │     │     └─ 推送到事件流
  │     │
  │     └─ 2.5 结束事件流
  │           ├─ 正常完成 → push(Done) + end()
  │           └─ 异常 → push(Error) + end()
  │
  └─ 3. 返回 AssistantMessageEventStream
```

#### 关键方法

| 方法 | 说明 |
|------|------|
| `buildPostRequest(url, body, headers)` | 构建 HTTP POST 请求，自动设置 Content-Type 和 Accept 头 |
| `sendWithRetry(request, options)` | 带重试的 HTTP 请求发送，支持指数退避 |
| `createInitialOutput(model)` | 创建初始的 AssistantMessage 输出对象 |
| `emitError(stream, output, error, signal)` | 发出错误事件 |
| `mergeHeaders(map...)` | 合并多个 HTTP 头 Map |
| `toJson(obj)` / `parseJson(str)` | JSON 序列化与反序列化 |

---

### 2.2 MessageTransformer.java — 消息转换器

**文件：** `common/MessageTransformer.java`

#### 作用

跨 Provider 的消息格式转换，将内部统一的 `Message` 类型转换为各 Provider 特有的请求格式。

#### 两遍处理算法

```
第一遍：预处理
  └─ 遍历所有消息，收集元数据
      ├─ 是否有系统提示？
      ├─ 是否有图片内容？
      └─ 是否有工具调用？

第二遍：转换
  └─ 遍历所有消息，逐条转换
      ├─ UserMessage → 用户消息格式
      │     ├─ TextContent → 文本
      │     └─ ImageContent → 图片（base64/URL）
      ├─ AssistantMessage → 助手消息格式
      │     ├─ TextContent → 文本
      │     ├─ ThinkingContent → 思考过程
      │     └─ ToolCall → 工具调用请求
      └─ ToolResultMessage → 工具结果格式
            └─ 工具执行结果
```

---

### 2.3 RetryPolicy.java — 重试策略

**文件：** `common/RetryPolicy.java`

#### 作用

实现智能的重试策略，根据 HTTP 状态码和异常类型决定是否重试以及等待多久。

#### 重试决策

| 状态码 | 是否重试 | 说明 |
|--------|---------|------|
| 2xx | 否 | 成功 |
| 429 | 是 | Rate Limit，等待 Retry-After 头或指数退避 |
| 500 | 是 | 服务器内部错误 |
| 502 | 是 | Bad Gateway |
| 503 | 是 | Service Unavailable |
| 504 | 是 | Gateway Timeout |
| 4xx（非 429） | 否 | 客户端错误（认证失败、参数错误等） |

#### 指数退避算法

```
等待时间 = baseDelay × 2^attempt + random(0, jitter)
```

- `baseDelay`：基础延迟（如 1 秒）
- `attempt`：已重试次数
- `jitter`：随机抖动，避免多个请求同时重试

---

### 2.4 SseParser.java — SSE 解析器

**文件：** `common/SseParser.java`

#### 作用

解析 SSE（Server-Sent Events）流。SSE 是 LLM 流式响应中最常用的传输协议。

#### SSE 格式

```
event: text_delta
data: {"type": "text_delta", "delta": "你好", ...}

event: done
data: {"type": "done", "message": {...}}
```

#### 解析逻辑

- 逐行读取输入流
- 解析 `event:` 行获取事件类型
- 解析 `data:` 行获取 JSON 数据
- 遇到空行（`\n\n`）表示一个完整事件结束
- 将解析结果回调给 Provider 的事件处理逻辑

---

## 三、内置提供者注册（builtin/ 包）— 1 个文件

### 3.1 BuiltInProviders.java

**文件：** `builtin/BuiltInProviders.java`

#### 作用

集中注册所有内置的 AI 提供商。在应用启动时调用 `BuiltInProviders.register()`，该方法会调用 `PiAi.setInitializer()` 将所有 Provider 注册到全局注册表中。

```java
public static void register() {
    PiAi.setInitializer(() -> {
        ApiProviderRegistry.register("anthropic-messages", new AnthropicProvider());
        ApiProviderRegistry.register("openai-responses", new OpenAIResponsesProvider());
        ApiProviderRegistry.register("openai-completions", new OpenAICompletionsProvider());
        // ...
    });
}
```

---

## 四、具体 Provider 实现

### 4.1 AnthropicProvider.java

**文件：** `anthropic/AnthropicProvider.java`

#### 对接 API

Anthropic Messages API（Claude 系列模型）

#### 关键实现

- **API 标识**：`"anthropic-messages"`
- **认证方式**：`x-api-key` 头
- **特殊头**：`anthropic-version: 2023-06-01`
- **消息格式**：Anthropic 特有的消息结构（role 为 "user"/"assistant"，content 为内容块数组）
- **SSE 事件名**：`message_start`、`content_block_start`、`content_block_delta`、`content_block_stop`、`message_delta`、`message_stop`
- **工具调用格式**：Anthropic 的 tool_use content block

---

### 4.2 OpenAI 系列

#### OpenAIResponsesProvider.java

**文件：** `openai/OpenAIResponsesProvider.java`

- **API 标识**：`"openai-responses"`
- **对接 API**：OpenAI Responses API（最新版）
- **认证方式**：`Authorization: Bearer` 头

#### OpenAICompletionsProvider.java

**文件：** `openai/OpenAICompletionsProvider.java`

- **API 标识**：`"openai-completions"`
- **对接 API**：OpenAI Completions API（传统版）
- **认证方式**：`Authorization: Bearer` 头

#### OpenAICodexResponsesProvider.java

**文件：** `openai/OpenAICodexResponsesProvider.java`

- **API 标识**：`"openai-codex-responses"`
- **对接 API**：OpenAI Codex Responses API（Codex 专用）

#### AzureOpenAIResponsesProvider.java

**文件：** `openai/AzureOpenAIResponsesProvider.java`

- **API 标识**：`"azure-openai-responses"`
- **对接 API**：Azure OpenAI Responses API
- **特殊处理**：Azure 特有的 endpoint 格式（`{resource}.openai.azure.com`）和认证方式（API Key 或 Entra ID）

---

### 4.3 Google 系列

#### GoogleGeminiProvider.java

**文件：** `google/GoogleGeminiProvider.java`

- **API 标识**：`"google-gemini"`
- **对接 API**：Google Gemini API（gemini-xxx 系列模型）
- **认证方式**：API Key 或 OAuth 令牌
- **特殊处理**：Google 特有的消息格式和安全设置

#### GoogleGeminiCliProvider.java

**文件：** `google/GoogleGeminiCliProvider.java`

- **API 标识**：`"google-gemini-cli"`
- **对接 API**：Google Gemini CLI API（本地 CLI 工具）

#### GoogleVertexProvider.java

**文件：** `google/GoogleVertexProvider.java`

- **API 标识**：`"google-vertex"`
- **对接 API**：Google Vertex AI（企业级平台）
- **认证方式**：ADC（Application Default Credentials）
- **特殊处理**：GCP 项目 ID、区域、access_token 获取

#### GoogleShared.java

**文件：** `google/GoogleShared.java`

Google 系列 Provider 的共享逻辑，包括：
- 共同的认证逻辑
- 共同的消息格式转换
- 共同的 API endpoint 构建

---

### 4.4 MistralProvider.java

**文件：** `mistral/MistralProvider.java`

- **API 标识**：`"mistral"`
- **对接 API**：Mistral AI API
- **认证方式**：`Authorization: Bearer` 头

---

### 4.5 BedrockProvider.java

**文件：** `bedrock/BedrockProvider.java`

- **API 标识**：`"bedrock"`
- **对接 API**：AWS Bedrock API
- **认证方式**：AWS Signature V4（使用 AWS 凭证）
- **特殊处理**：AWS 区域、IAM 角色、AssumeRole 支持

---

## 五、架构关系图

```
PiAi（统一入口）
  │
  ├─ ensureInitialized()
  │     └─ BuiltInProviders.register()
  │           └─ ApiProviderRegistry.register("anthropic-messages", AnthropicProvider)
  │           └─ ApiProviderRegistry.register("openai-responses", OpenAIResponsesProvider)
  │           └─ ...
  │
  └─ resolveProvider(model.api())
        │
        ├─ "anthropic-messages" → AnthropicProvider
        │     └─ BaseProvider 骨架
        │           ├─ buildPostRequest(url, body, headers)
        │           ├─ sendWithRetry(request, options)
        │           └─ SSE 解析 → 事件流
        │
        ├─ "openai-responses" → OpenAIResponsesProvider
        │     └─ BaseProvider 骨架
        │
        ├─ "google-gemini" → GoogleGeminiProvider
        │     └─ BaseProvider 骨架
        │
        └─ ...
```

---

## 六、学习检查清单

1. [ ] 提供者模式（Provider Pattern）的不变部分和可变部分各是什么？
2. [ ] `BaseProvider` 提供了哪些通用能力？子类需要实现哪些方法？
3. [ ] `sendWithRetry` 的重试策略是什么？哪些状态码会触发重试？
4. [ ] 指数退避算法是如何计算的？
5. [ ] SSE 解析器如何处理流式事件？
6. [ ] `MessageTransformer` 的两遍处理算法是什么？
7. [ ] `BuiltInProviders.register()` 做了什么事情？它如何与 `PiAi` 配合？
8. [ ] Anthropic、OpenAI、Google 的认证方式各有什么不同？
9. [ ] 各 Provider 的消息格式转换有什么主要差异？
10. [ ] 如果新增一个 AI 提供商，需要实现哪些步骤？

---

## 七、下一步

完成阶段四后，进入阶段五（pi-coding-agent）学习完整的编码智能体应用。
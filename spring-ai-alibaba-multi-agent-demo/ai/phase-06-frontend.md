# 第六阶段：前端（Vue 3 全链路）

> 理解前端如何调用 Agent 的 SSE 流式接口，完成从用户输入到 AI 回复的全链路

---

## 整体交互流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant FE as 前端 :3000<br/>Vue 3 + Ant Design Vue
    participant SA as SupervisorAgent :10008
    participant SUB as 子 Agent
    participant MCP as MCP Server

    U->>FE: 输入"我想买一杯云边茉莉"
    FE->>SA: GET /api/assistant/chat?chat_id=xxx&user_query=xxx&user_id=xxx
    Note over FE,SA: SSE 连接建立

    SA->>SA: LLM 分析意图 → 路由到 order_agent
    SA->>SUB: A2A 协议调用
    SUB->>MCP: MCP 协议调用工具
    MCP-->>SUB: 工具返回结果
    SUB-->>SA: 流式返回
    SA-->>FE: SSE: "好的，"
    SA-->>FE: SSE: "我来帮您下单..."
    SA-->>FE: SSE: "云边茉莉，半糖去冰，"
    SA-->>FE: SSE: "订单已创建！"

    FE->>FE: 逐字渲染到聊天界面
    U->>U: 看到完整回复
```

---

## 前端技术栈

```
Vue 3 + Vite + Ant Design Vue + EventSource (SSE)
```

---

## 核心：SSE 流式消费

### 前端 EventSource 调用

```javascript
// 前端发起 SSE 连接
const eventSource = new EventSource(
    `/api/assistant/chat?chat_id=${chatId}&user_query=${userQuery}&user_id=${userId}`
);

// 接收流式消息
eventSource.onmessage = (event) => {
    // event.data 是 AI 返回的文本片段
    appendToChat(event.data);  // 逐字追加到界面
};

// 流结束
eventSource.addEventListener('done', () => {
    eventSource.close();
});

// 错误处理
eventSource.onerror = (error) => {
    console.error('SSE connection error:', error);
    eventSource.close();
};
```

### 为什么用 SSE 而不是 WebSocket

| 维度 | SSE | WebSocket |
|------|-----|-----------|
| **方向** | 单向（服务器→客户端） | 双向 |
| **复杂度** | 简单（浏览器原生 EventSource） | 复杂（需要额外库） |
| **适合场景** | AI 流式输出 | 实时聊天、游戏 |
| **本项目场景** | 用户发一条消息，AI 逐字返回 | — |

---

## 全链路数据流

```mermaid
graph TD
    subgraph "前端"
        U[用户输入] --> INPUT[文本框]
        INPUT --> SSECLIENT[EventSource 连接]
        SSECLIENT --> RENDER[逐字渲染]
    end

    subgraph "SupervisorAgent :10008"
        SSECLIENT -->|GET /api/assistant/chat| CTRL[SupervisorAgentController]
        CTRL --> AGENT[LlmRoutingAgent]
        AGENT --> GRAPH[CompiledGraph]
        GRAPH -->|fluxStream| PROCESS[processStream]
        PROCESS -->|SSE| SSECLIENT
    end

    subgraph "子 Agent"
        GRAPH -->|A2A a2aNode| SUB[ReactAgent]
        SUB --> TOOLS[工具调用]
    end

    subgraph "MCP Server"
        TOOLS -->|MCP 协议| MCP[MCP Tools]
        MCP --> DB[(MySQL/Mem0)]
    end
```

---

## 关键 API 端点

| 端点 | 方法 | 用途 |
|------|------|------|
| `/api/assistant/chat` | GET | 用户端对话（SSE 流式） |
| `/api/admin/chat` | GET | 管理端对话（SSE 流式） |
| `/api/consult_sub_agent/debug` | GET | 咨询 Agent 调试（直连） |

---

## processStream 过滤了什么

```java
// 后端 Controller 中的过滤逻辑
generator
    .filter(output -> "a2aNode".equals(output.node())  // 只要子 Agent 的输出
            && output instanceof StreamingOutput)
    .cast(StreamingOutput.class)
    .map(StreamingOutput::chunk)                       // 提取文本块
    .filter(content -> content != null
            && !content.isEmpty()
            && !content.equals("Agent State: submitted")) // 过滤状态消息
    .map(content -> ServerSentEvent.builder(content).build())
```

**前端看到的**：只有子 Agent 的实际回复内容，看不到内部路由决策和状态消息。

---

## 第六阶段总结

| 学到什么 | 关键概念 |
|---------|---------|
| SSE 流式消费 | EventSource API、单向推送 |
| 全链路数据流 | 前端 → SupervisorAgent → 子Agent → MCP Server → DB |
| processStream 过滤 | 只要 a2aNode 的输出，过滤状态消息 |
| 为什么不用 WebSocket | AI 对话只需要单向流，SSE 更简单 |

---

## 完整学习路线回顾

```mermaid
graph LR
    A[第一阶段<br/>入口与总览] --> B[第二阶段<br/>监督者 Agent]
    B --> C[第三阶段<br/>子 Agent]
    C --> D[第四阶段<br/>MCP Server]
    D --> E[第五阶段<br/>定时任务]
    E --> F[第六阶段<br/>前端]

    style A fill:#90EE90
    style B fill:#FFD700
    style C fill:#FFA500
    style D fill:#FF6347
    style E fill:#9B59B6
    style F fill:#3498DB
```

### 核心知识体系

```
                    ┌──────────────┐
                    │  Supervisor  │  ← LlmRoutingAgent + A2A 协议 + SSE 流式输出
                    │  (路由分发)   │
                    └──────┬───────┘
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
    ┌──────────┐   ┌──────────┐   ┌──────────┐
    │ Consult  │   │  Order   │   │ Feedback │  ← ReactAgent + 本地Tool + MCP远程Tool
    │  Agent   │   │  Agent   │   │  Agent   │
    └────┬─────┘   └────┬─────┘   └────┬─────┘
         │              │              │
         └──────────────┼──────────────┘
                        │ MCP 协议
         ┌──────────────┼──────────────┐
         ▼              ▼              ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ Memory   │ │  Order   │ │ Feedback │  ← MCP Server: @Tool 暴露为远程服务
    │  MCP     │ │  MCP     │ │  MCP     │
    └──────────┘ └──────────┘ └──────────┘

                    ┌──────────────┐
                    │  定时任务     │  ← StateGraph + NodeAction + IterationNode
                    │  日报/评价    │     + XXL-JOB 调度
                    └──────────────┘
```

---

> 文档生成日期：2026-07-09
> 项目：spring-ai-alibaba-multi-agent-demo v1.0.0
> 返回：[INDEX.md](./INDEX.md)
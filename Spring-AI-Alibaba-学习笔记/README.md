# Spring AI Alibaba 学习笔记

> 从 helloworld 到综合实战的完整学习路径。
> 每个模块独立成文，包含架构图、关键代码、运行链路和学习总结。

---

## 学习路线图

```mermaid
graph LR
    A[01 helloworld] --> B[02 chat-example]
    B --> C[03 prompt-example]
    C --> D[04 structured-example]
    D --> E[05 chat-memory-example]
    E --> F[06 tool-calling-example]
    F --> G[07 react-agent-example]
    G --> H[08 graph-example/react]
    H --> I[09 graph-example/parallel-node]
    I --> J[10 llm-auditor]
    J --> K[11 subagent-assistant]
    K --> L[12 four-paradigm-combined]
    L --> M[13 rag-example]
    M --> N[14 rag-agent-example]
    N --> O[15 playground-flight-booking]

    style A fill:#90EE90
    style B fill:#90EE90
    style C fill:#90EE90
    style D fill:#90EE90
    style E fill:#90EE90
    style F fill:#FFD700
    style G fill:#FFA500
    style H fill:#FFA500
    style I fill:#FF6347
    style J fill:#FF6347
    style K fill:#FF6347
    style L fill:#9B59B6
    style M fill:#1ABC9C
    style N fill:#1ABC9C
    style O fill:#3498DB
```

## 模块索引

| 阶段 | 序号 | 模块 | 文档 | 核心能力 |
|------|------|------|------|---------|
| 基础层 | 01 | helloworld | [01-helloworld.md](./01-helloworld.md) | 跑通最小调用 |
| 基础层 | 02 | chat-example | [02-chat-example.md](./02-chat-example.md) | ChatClient / ChatModel 调用层 |
| 基础层 | 03 | prompt-example | [03-prompt-example.md](./03-prompt-example.md) | Prompt 模板、角色设定、上下文填充 |
| 基础层 | 04 | structured-example | [04-structured-example.md](./04-structured-example.md) | 结构化输出 |
| 基础层 | 05 | chat-memory-example | [05-chat-memory-example.md](./05-chat-memory-example.md) | 多轮记忆 |
| 工具层 | 06 | tool-calling-example | [06-tool-calling-example.md](./06-tool-calling-example.md) | 让 AI 调用代码 |
| 智能体 | 07 | react-agent-example | [07-react-agent-example.md](./07-react-agent-example.md) | ReAct 智能体 |
| 智能体 | 08 | graph-example/react | [08-graph-react.md](./08-graph-react.md) | Graph 实现 ReAct |
| 智能体 | 09 | graph-example/parallel-node | [09-graph-parallel-node.md](./09-graph-parallel-node.md) | 并行编排 |
| 高级 | 10 | adk-samples-llm-auditor | [10-llm-auditor.md](./10-llm-auditor.md) | Reflection 范式 |
| 高级 | 11 | subagent-personal-assistant | [11-subagent-personal-assistant.md](./11-subagent-personal-assistant.md) | Multi-Agent / Supervisor |
| 综合 | 12 | four-paradigm-combined | [12-four-paradigm-combined.md](./12-four-paradigm-combined.md) | 四范式合一 |
| 应用 | 13 | rag-example | [13-rag-example.md](./13-rag-example.md) | 传统 RAG：检索增强生成 |
| 应用 | 14 | rag-agent-example | [14-rag-agent.md](./14-rag-agent.md) | Agentic RAG：ReAct + 检索工具 |
| 毕业 | 15 | playground-flight-booking | [15-playground-flight-booking.md](./15-playground-flight-booking.md) | 综合实战：ChatClient + Advisor |

## 三大阶段

| 阶段 | 覆盖模块 | 核心能力 |
|------|------|---------|
| 基础层 | 01-05 | 让 LLM 回答、按模板回答、输出结构化、记住上下文 |
| 工具与智能体层 | 06-12 | 让 LLM 调代码、循环推理、并行编排、多 Agent 协作 |
| 应用与落地层 | 13-15 | RAG 知识库、Agentic RAG、业务系统综合实战 |

## 核心心智模型

```text
所有 LLM 应用都在做一件事：
  在“调用模型”之前和之后加处理层，用工程手段补齐模型限制。

裸调模型      -> helloworld / chat-example
控制输入      -> prompt-example
控制输出      -> structured-example
补上下文      -> chat-memory-example
调用业务代码  -> tool-calling-example
多步自主决策  -> react-agent / graph
多角色协作    -> reflection / supervisor
补外部知识    -> rag-example / rag-agent
业务落地      -> playground-flight-booking
```

## 两条主线

| 主线 | 代表模块 | 适合场景 |
|---|---|---|
| ChatClient + Advisor | 03、04、05、13、15 | 轻量问答、客服、知识库、常规业务应用 |
| ReactAgent / Graph | 07、08、09、10、11、12、14 | 多步推理、工具循环、审批暂停、多 Agent 编排 |

## Prompt、RAG、Agent 的关系

```text
prompt-example:
  手动组织模型输入

rag-example:
  自动检索上下文，再组织模型输入

rag-agent-example:
  把检索封装成工具，让 Agent 自主决定何时检索
```

这三站要连起来看：Prompt 是输入控制，RAG 是自动补知识，Agentic RAG 是把“补知识”升级成可自主决策的工具。

## 下一步学习路径

当前主线补齐后，建议继续按这个顺序扩展：

```text
MCP 协议
-> Observability 可观测性
-> Multimodal 图像/音频/视频
```

原因：

- MCP 是工具层标准化升级，最贴近 Agent 方向
- Observability 是生产落地需要的 trace、metric、日志和排障能力
- Multimodal 更偏模型能力扩展，可以后置

## 四大 Agent 范式

```text
ReAct (第 7-8 站):
  单 Agent + 工具循环

并行 (第 9 站):
  fan-out / fan-in

Reflection (第 10 站):
  生成 -> 审查 -> 修订

Multi-Agent / Supervisor (第 11 站):
  主 Agent 调度子 Agent
```

第 12 站 `four-paradigm-combined` 把这四种范式组合到同一个示例里。

## 关键概念速查

| 概念 | 一句话解释 |
|------|-----------|
| ChatModel | 底层 LLM 引擎，返回完整 ChatResponse |
| ChatClient | 高级 Fluent API，链式调用 |
| PromptTemplate | 提示词模板，支持占位符变量 |
| Advisor | LLM 调用层拦截器，可在 before/after 改请求和响应 |
| BeanOutputConverter | 把 LLM 输出转换成 Java Bean |
| ChatMemory | 对话历史持久化 |
| Tool Calling | 让 LLM 选择并调用业务函数 |
| VectorStore | 存储和检索向量化文档 |
| RAG | 检索相关文档后增强生成 |
| ReactAgent | ReAct 范式的开箱即用实现 |
| StateGraph | 显式编排节点和边 |
| OverAllState | Graph 的共享状态 |
| Saver | 图执行状态快照，支持暂停/恢复 |
| HITL | Human-In-The-Loop，人工介入 |
| ModelHook | Agent 内部调用模型前后的钩子 |
| Agent as Tool | 把子 Agent 包装成工具交给上级 Agent 调用 |

---

> 文档更新日期：2026-07-07
> 覆盖模块：15 个核心学习站点，包含 2 个补充站点（Prompt、基础 RAG）。

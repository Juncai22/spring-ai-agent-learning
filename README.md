# Spring AI Alibaba Examples

> 学习Spring AI ，学习Java Agent开发，这个就足够了！！

> Spring AI Alibaba Repo: https://github.com/alibaba/spring-ai-alibaba
>
> Spring AI Alibaba Website:  https://java2ai.com
>
> Spring AI Alibaba Website Repo: https://github.com/springaialibaba/spring-ai-alibaba-website

[English](./README-en.md) | 中文

## 关于本仓库（个人学习版）

> 这是我在 [spring-ai-alibaba](https://github.com/alibaba/spring-ai-alibaba) 官方示例仓库基础上的**个人学习与注释版**，用于系统学习 Spring AI 与 Spring AI Alibaba 的各种用法和最佳实践。

本仓库在原项目的基础上做了以下**个人学习层面的增强**：

- 📝 **大量中文注释**：在源码（包括 Controller、Service、Configuration、Graph 节点、Advisor、Tool Calling、RAG 等核心类）中添加了较为详尽的中文注释，帮助理解每一步的意图、关键参数与调用链路。
- 📒 **学习笔记**：在部分模块下附带了 `notes/` 或 `学习笔记.md`，记录学习过程中的**踩坑记录、关键知识点总结、与官方文档的差异、个人理解**等内容。
- 🧪 **本地验证**：所有跑通过的示例都附带实际运行结果、请求/响应样例，方便回看。
- 🗂 **结构化整理**：对模块按主题（Chat / RAG / Agent / MCP / Graph / Multimodal / Observability 等）做了归类与索引。
- 🧩 **个人新增的综合模块**：例如 [`spring-ai-alibaba-agent-example/four-paradigm-combined-example`](./spring-ai-alibaba-agent-example/four-paradigm-combined-example/README.md) —— **「四范式合一」** 示例（`ReAct + 并行 + Reflection + Supervisor`），把四种 Agent 范式在同一个"智能内容创作系统"中协同跑通，作为阶段性学习的小综合。

### 学习节奏（个人记录）

> ⏱ **预估约 3 天时间能把主线程学完**（Hello World → Chat → Prompt → Structured Output → Memory → Tool Calling → ReAct / Reflection / Supervisor / ParallelNode → 基础 RAG / RAG Agent → Playground 综合实战），期间每个核心节点都会留下注释和笔记。

> 如果你只是想查看官方示例本身，请直接访问上游仓库；本仓库更偏向**"带着注释的学习版"**，适合和我一样正在系统学习 Spring AI 生态的同学参考。

## 介绍

此仓库中包含许多 Example 模块项目来介绍 Spring AI 和 Spring AI Alibaba 从基础到高级的各种用法和 AI 项目的最佳实践。

更详细的介绍请参阅每个子项目中的 README.md 和 [Spring AI Alibaba 官网](https://java2ai.com)。

## 新增综合项目

### 云边奶茶铺多智能体购买系统

目录：[`spring-ai-alibaba-multi-agent-demo`](./spring-ai-alibaba-multi-agent-demo/README.md)

这是一个基于 Spring AI Alibaba Agentic API 的分布式多智能体奶茶导购与购买示例。用户可以在同一段对话中完成产品咨询、个性化推荐、下单、订单查询以及反馈投诉；系统还会持续记录用户的口味和消费习惯，让后续推荐与下单更贴合用户偏好。

项目采用“监督者智能体 + 业务子智能体 + MCP 工具服务”的分层设计：

| 层级 | 模块 | 主要职责 |
|---|---|---|
| 交互层 | `frontend` | Vue 3 聊天界面，通过 SSE 展示流式回复 |
| 路由层 | `supervisor-agent` | 使用 `LlmRoutingAgent` 判断用户意图，并通过 A2A 协议把请求转发给对应子智能体 |
| 业务层 | `consult-sub-agent` | 结合阿里云百炼知识库进行产品检索、咨询和个性化推荐 |
| 业务层 | `order-sub-agent` | 负责商品校验、库存查询、创建订单、订单查询与修改 |
| 业务层 | `feedback-sub-agent` | 处理评价、投诉和解决方案，并从反馈中提取用户偏好 |
| 工具层 | `order-mcp-server`、`feedback-mcp-server` | 通过 MCP 暴露订单与反馈的数据库操作能力 |
| 记忆层 | `memory-mcp-server` | 对接 Mem0，统一存储和检索用户的长期偏好记忆 |

核心调用链为：`前端 -> Supervisor Agent -> A2A 子 Agent -> MCP Server -> MySQL/Mem0`。三个子智能体和 MCP 服务通过 Nacos 完成注册与发现；咨询智能体还使用 DashScope/百炼 RAG 检索品牌及产品知识。管理端提供可选的定时 Agent，能够配合 XXL-JOB 分析用户评价和消费数据、生成运营日报并发送钉钉通知。

运行前需要 Java 17、Maven、Node.js 20+、Docker，以及 DashScope、百炼知识库和 Mem0 等配置。先根据 [`env.template`](./spring-ai-alibaba-multi-agent-demo/env.template) 创建 `.env`，再启动 MySQL、Nacos、Redis，最后在模块目录执行：

```bash
./build.sh
```

服务启动顺序、端口和故障排查请参阅[模块 README](./spring-ai-alibaba-multi-agent-demo/README.md)；分阶段源码导读请参阅[学习文档索引](./spring-ai-alibaba-multi-agent-demo/ai/INDEX.md)。

### PI Java 多模型 SDK 与编码智能体

目录：[`pi-momo-java`](./pi-momo-java/ai-doc/LEARNING_ROADMAP.md)

这是一个面向 Java 17 的独立 Maven 多模块项目，将统一多模型 LLM API、Agent Runtime 和 Coding Agent 能力组合为一套 Java SDK。它不是 Spring Boot Web 服务，也没有可直接启动的 Controller；主要用途是作为底层库嵌入 Java 应用，或通过 SDK/RPC 模式构建自己的编码智能体。

| 模块 | 定位 | 主要能力 |
|---|---|---|
| `pi-ai-core` | AI 基础层 | 统一消息/内容/工具类型、模型注册表、Token 用量与成本、可取消的流式 `EventStream`、`PiAi.stream()` 调用入口 |
| `pi-ai-providers` | 模型适配层 | 适配 Anthropic、OpenAI/Azure、Google Gemini/Vertex、Mistral、Amazon Bedrock 等 API，统一处理 SSE、消息转换与重试 |
| `pi-ai-oauth` | 认证层 | 为 Anthropic、GitHub Copilot、Gemini CLI、Google Antigravity 和 OpenAI Codex 提供 OAuth/PKCE 支持 |
| `pi-agent-core` | Agent 运行时 | 实现 Agent 状态、生命周期事件、消息队列、串行/并行工具调用、调用前后钩子、干预与跟进消息，以及完整 Agent Loop |
| `pi-coding-agent` | 编码智能体层 | 提供 `read`、`bash`、`edit`、`write`、`grep`、`find`、`ls` 工具，并包含会话树、上下文压缩、Skills/提示词热加载、扩展系统、设置管理、RPC 和 SDK 入口 |

项目的核心调用链为：`CodingAgentSdk/Agent -> AgentLoop -> PiAi.stream() -> ApiProvider -> 模型流式 API`。当模型返回工具调用时，Agent Loop 会校验参数、执行工具、把结果写回上下文并继续下一轮推理，直到模型正常结束或任务被取消。

该项目拥有自己的父 POM，目前未加入仓库根 POM 的 Maven Reactor。请使用独立 POM 构建和测试：

```bash
mvn -f pi-momo-java/pom.xml clean test
```

建议按 [`pi-ai-core -> pi-agent-core -> pi-ai-oauth -> pi-ai-providers -> pi-coding-agent`](./pi-momo-java/ai-doc/LEARNING_ROADMAP.md) 的顺序阅读，详细的五阶段学习资料位于 [`pi-momo-java/ai-doc`](./pi-momo-java/ai-doc/)。

## 参与建设

欢迎任何形式的代码贡献。

## Quickstart Matrix (Community Contribution)

| module | purpose | command | required services | env vars | env template | entry |
|---|---|---|---|---|---|---|
| spring-ai-alibaba-helloworld | basic chat and advisor examples | `mvn -pl spring-ai-alibaba-helloworld spring-boot:run` | none | `AI_DASHSCOPE_API_KEY` | — | [README](./spring-ai-alibaba-helloworld/README.md) |
| spring-ai-alibaba-chat-example/dashscope-chat | DashScope chat basics | `mvn -pl spring-ai-alibaba-chat-example/dashscope-chat spring-boot:run` | none | `AI_DASHSCOPE_API_KEY` | — | [README](./spring-ai-alibaba-chat-example/dashscope-chat/README.md) |
| spring-ai-alibaba-prompt-example | prompt templates and context stuffing | `mvn -pl spring-ai-alibaba-prompt-example spring-boot:run` | none | `AI_DASHSCOPE_API_KEY` | — | [README](./spring-ai-alibaba-prompt-example/README.md) |
| spring-ai-alibaba-image-example/dashscope-image | DashScope image generation | `mvn -pl spring-ai-alibaba-image-example/dashscope-image spring-boot:run` | none | `AI_DASHSCOPE_API_KEY` | — | [README](./spring-ai-alibaba-image-example/dashscope-image/README.md) |
| spring-ai-alibaba-mcp-example | MCP demo | `mvn -pl spring-ai-alibaba-mcp-example spring-boot:run` | none/local mcp tool | model api key | [`.env.example`](./spring-ai-alibaba-mcp-example/.env.example) | [README](./spring-ai-alibaba-mcp-example/README.md) |
| spring-ai-alibaba-rag-example | RAG demo | `mvn -pl spring-ai-alibaba-rag-example spring-boot:run` | vector db (optional by profile) | model api key, embedding model | [`.env.example`](./spring-ai-alibaba-rag-example/.env.example) | [README](./spring-ai-alibaba-rag-example/README.md) |
| spring-ai-alibaba-tool-calling-example | tool calling | `mvn -pl spring-ai-alibaba-tool-calling-example spring-boot:run` | none | model api key, map api key | [`.env.example`](./spring-ai-alibaba-tool-calling-example/.env.example) | [README](./spring-ai-alibaba-tool-calling-example/README.md) |
| spring-ai-alibaba-multi-agent-demo | 奶茶咨询、购买与反馈多智能体系统 | `cd spring-ai-alibaba-multi-agent-demo && ./build.sh` | MySQL, Nacos, Redis, Node.js 20+ | DashScope、百炼知识库、Mem0、OpenAI-compatible API | [`env.template`](./spring-ai-alibaba-multi-agent-demo/env.template) | [README](./spring-ai-alibaba-multi-agent-demo/README.md) |
| pi-momo-java | Java 多模型 SDK 与 Coding Agent Runtime | `mvn -f pi-momo-java/pom.xml clean test` | none | 仅实际调用模型时需要对应 Provider 凭证 | — | [学习路线](./pi-momo-java/ai-doc/LEARNING_ROADMAP.md) |

## 常用配置键速查

| 配置键 | 常见模块 | 说明 |
|---|---|---|
| `AI_DASHSCOPE_API_KEY` | `spring-ai-alibaba-helloworld`、DashScope chat/image、tool calling、evaluation、很多 graph/rag 示例 | DashScope 兼容模型最常见的 API Key |
| `OPENAI_API_KEY` | `spring-ai-alibaba-chat-example/openai-chat`、`spring-ai-alibaba-chat-example/vllm-chat` | OpenAI 兼容接口示例常用 |
| `AI_OPENAI_API_KEY` | `spring-ai-alibaba-image-example/openai-image` | OpenAI 图片生成示例使用 |
| `AI_DEEPSEEK_API_KEY` | `spring-ai-alibaba-chat-example/deepseek-chat`、`spring-ai-alibaba-mem0-example` | DeepSeek 相关示例使用 |
| `MINIMAX_API_KEY` | `spring-ai-alibaba-chat-example/minimax-chat` | MiniMax 模型示例使用 |
| `ZHIPUAI_API_KEY` | `spring-ai-alibaba-chat-example/zhipuai-chat` | 智谱模型示例使用 |
| `BAIDU_MAP_API_KEY` | `spring-ai-alibaba-tool-calling-example` | 地图工具调用示例需要 |

## 常见启动问题 / Troubleshooting

- `AI_DASHSCOPE_API_KEY` 未设置 / Missing `AI_DASHSCOPE_API_KEY`: 先确认环境变量已在当前 shell 或 IDE 中生效，再重新启动示例。
- 端口被占用 / Port already in use: 检查对应模块 `application.yml` 中的 `server.port`，释放端口或改端口后重试。
- 本地依赖未启动 / Required local services not running: RAG、MCP、向量库或 Docker 相关示例通常需要先启动对应的中间件或容器。
- 模块里暂时没有 `.env.example` / No `.env.example` in a module yet: 优先查看该模块 README 和 `src/main/resources/application.yml`，确认真实的变量名和依赖服务。

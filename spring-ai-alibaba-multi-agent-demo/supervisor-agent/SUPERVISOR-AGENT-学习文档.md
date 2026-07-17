# Supervisor Agent 模块深度解析

> 从 Spring AI Alibaba 学习笔记到生产级 Multi-Agent 系统的桥梁

---

## 一、模块定位

`supervisor-agent` 是整个云边奶茶铺多智能体系统的**神经中枢**，负责：

1. **接收用户请求** → 通过 HTTP SSE 接口暴露给前端
2. **智能路由分发** → 使用 LLM 分析意图，将请求转发给合适的子 Agent
3. **管理定时任务** → 支持管理员创建定时运行的 Agent（日报、分析等）

### 在系统架构中的位置

```
┌─────────────────────────────────────────────────────┐
│ 前端 (Vue.js @ localhost:3000)                       │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP SSE
┌──────────────────────▼──────────────────────────────┐
│ ★ Supervisor Agent (本模块 @ localhost:10008)        │
│                                                      │
│  ┌─────────────────────┐  ┌──────────────────────┐  │
│  │ SupervisorAgent     │  │ AdminAgent           │  │
│  │ (LlmRoutingAgent)   │  │ (LlmRoutingAgent)    │  │
│  │ 用户端路由 → 子Agent │  │ 管理端路由 → 定时任务 │  │
│  └─────────┬───────────┘  └──────────┬───────────┘  │
│            │ A2A协议                  │ 本地调用      │
└────────────┼──────────────────────────┼──────────────┘
             │                          │
     ┌───────┼───────┬──────────┐       │
     ▼       ▼       ▼          ▼       ▼
  consult  order  feedback   CronTaskParseAgent
  SubAgent SubAgent SubAgent (定时任务解析)
```

---

## 二、文件清单与学习顺序

| 优先级 | 文件 | 核心知识点 | 对应笔记 |
|--------|------|-----------|---------|
| ★★★ | `SupervisorAgent.java` | LlmRoutingAgent + A2A 协议 | 模块 11, 12 |
| ★★★ | `SanitizingRoutingChatModel.java` | 装饰器模式、LLM 输出清洗 | 模块 02 |
| ★★★ | `SupervisorAgentController.java` | SSE 流式输出、Graph 执行 | 模块 08, 15 |
| ★★ | `AdminAgent.java` | 管理员路由 Agent | 模块 11 |
| ★★ | `AdminAgentController.java` | 管理端 API | 模块 15 |
| ★ | `SupervisorAgentPromptConfig.java` | @ConfigurationProperties | 模块 03 |
| ★ | `CorsConfig.java` | CORS 跨域配置 | — |
| ★ | `SupervisorAgentApplication.java` | Spring Boot 启动类 | — |
| ★★ | `CronAgentConfiguration.java` | ReactAgent + 工具调用 | 模块 07 |
| ★★ | `CronAgentTools.java` | @Tool 注解、定时任务注册 | 模块 06 |
| ★★ | `XxlJobConfig.java` | XXL-JOB 集成 | 🆕 |
| ★★ | `XxlJobScheduledAgentManager.java` | 分布式任务调度适配器 | 🆕 |
| ★★★ | `DailyReportAgentConfiguration.java` | StateGraph 手写编排 + LLM 模板 | 模块 08, 09 |
| ★★★ | `EvaluationAgentConfiguration.java` | IterationNode 迭代节点 | 模块 09 |
| ★ | `DingMessageSenderNode.java` | 自定义 Graph 节点 | 模块 08 |
| ★ | `EvaluationClassifierNode.java` | LLM 分类节点 | 模块 04 |
| ★ | `SessionFileReader.java` | 文件读取工具 | — |

---

## 三、核心架构详解

### 3.1 两条 Agent 路线对比

本模块同时使用了两种 Agent 模式：

| 维度 | SupervisorAgent | AdminAgent |
|------|----------------|------------|
| **服务对象** | C 端用户（咨询/下单/反馈） | B 端管理员（定时任务配置） |
| **子 Agent 类型** | A2aRemoteAgent（远程微服务） | BaseAgent（本地 ReactAgent） |
| **通信协议** | A2A（Agent-to-Agent） | 本地方法调用 |
| **子 Agent 数量** | 3 个（consult/order/feedback） | 1 个（CronTaskParseAgent） |
| **输入 key** | `input` | `user_query` |
| **输出 key** | `messages` | `agent_input` |

### 3.2 A2A 协议工作原理

```
┌──────────────────────────────────────────────────────┐
│                    Nacos 注册中心                      │
│                                                       │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐  │
│  │AgentCard:    │ │AgentCard:    │ │AgentCard:    │  │
│  │consult_agent │ │order_agent   │ │feedback_agent│  │
│  │url:10005     │ │url:10006     │ │url:10007     │  │
│  └──────┬───────┘ └──────┬───────┘ └──────┬───────┘  │
└─────────┼────────────────┼────────────────┼──────────┘
          │                │                │
          ▼                ▼                ▼
┌──────────────────────────────────────────────────────┐
│              SupervisorAgent (A2A Client)             │
│                                                       │
│  1. AgentCardProvider.getAgentCard("consult_agent")   │
│  2. A2aRemoteAgent 封装远程调用                        │
│  3. LlmRoutingAgent 路由 + 调用                       │
└──────────────────────────────────────────────────────┘
```

**A2A 协议的关键概念：**

- **AgentCard**：子 Agent 的"名片"，包含名称、描述、URL、能力声明
- **AgentCardProvider**：从注册中心获取 AgentCard 的提供者（本项目使用 Nacos）
- **A2aRemoteAgent**：远程 Agent 的本地代理，封装了网络通信细节
- **LlmRoutingAgent**：内置的路由型 Agent，LLM 决定路由 + A2A 执行调用

### 3.3 LlmRoutingAgent 内部 Graph 结构

```
                  ┌──────────┐
                  │  START   │
                  └────┬─────┘
                       │
                  ┌────▼─────┐
                  │  preLlm  │ ← 准备上下文（处理 state 中的 input/chat_id/user_id）
                  └────┬─────┘
                       │
                  ┌────▼─────┐
                  │   llm    │ ← LLM 分析意图 + 路由决策
                  │          │   输入："我想买一杯奶茶"
                  │          │   输出："consult_agent" 或 "order_agent"
                  └────┬─────┘
                       │
                  ┌────▼─────┐
                  │ a2aNode  │ ← 通过 A2A 协议调用子 Agent
                  │          │   流式返回子 Agent 的响应
                  └────┬─────┘
                       │
                  ┌────▼─────┐
                  │   END    │
                  └──────────┘
```

**前端只看到 a2aNode 的流式输出**，preLlm 和 llm 节点的输出是内部的，不对外暴露。

### 3.4 SanitizingRoutingChatModel 的设计

**问题**：LLM 的路由输出可能包含多余文字，例如：
```
思考：用户想点奶茶，应该调用订单相关的Agent来处理。
我认为应该调用 order_agent 来处理这个请求。
```

**解决方案**：装饰器模式，对 LLM 输出做两步清洗：
1. 正则过滤 ` thinking... response` 思考块
2. 提取最后一个出现的子 Agent 名称（如 `order_agent`）

---

## 四、数据流全景图

### 4.1 用户请求流（C 端）

```
用户输入："我想点一杯云边茉莉，半糖去冰"
    │
    ▼
SupervisorAgentController.chat(chat_id, user_query, user_id)
    │
    ├── 构建 input: "我想点一杯云边茉莉，半糖去冰<userId>10001</userId>"
    │
    ▼
LlmRoutingAgent (supervisorAgentBean)
    │
    ├── preLlm: 准备 state
    ├── llm: LLM 分析 → "这是下单请求，路由到 order_agent"
    ├── a2aNode: 通过 A2A 调用 order_agent（端口 10006）
    │     └── order_agent 内部调用 OrderMCP Server 的工具
    │           └── 创建订单、库存检查、记录偏好到 Mem0
    └── 流式返回结果 → SSE 推送到前端
```

### 4.2 管理员定时任务流（B 端）

```
管理员输入："每天8点执行经营日报"
    │
    ▼
AdminAgentController.chat(chat_id, user_query)
    │
    ▼
LlmRoutingAgent (adminAgentBean)
    │
    ├── llm: 路由到 CronTaskParseAgent
    ├── a2aNode: 调用 CronTaskParseAgent（本地 ReactAgent）
    │     └── CronTaskParseAgent 调用 createCronAgent 工具
    │           ├── 解析 cron 表达式: "0 0 8 * * ?"
    │           ├── 查找 dailyReportAgent Bean
    │           └── agent.schedule(ScheduleConfig) 注册定时任务
    └── 返回 "成功创建了一个 0 0 8 * * ? 的定时Agent"
```

### 4.3 定时任务执行流

```
XXL-JOB 触发 (每天 8:00)
    │
    ▼
XxlJobScheduledAgentManager 执行
    │
    ▼
DailyReportAgent (CompiledGraph) 执行
    │
    ├── data_loader: 从 MySQL 查询订单/反馈数据 + 计算统计指标
    ├── data_analysis: LLM 根据模板生成日报
    └── message_sender: 通过钉钉 Webhook 发送日报
```

---

## 五、关键设计模式与最佳实践

### 5.1 装饰器模式 —— SanitizingRoutingChatModel

```java
// 包装原始 ChatModel，对输出做清洗，对调用者透明
ChatModel routingChatModel = new SanitizingRoutingChatModel(
    chatModel,                          // 被装饰的 ChatModel
    List.of("consult_agent", ...));     // 有效的路由目标
```

### 5.2 Builder 模式 —— 所有 Agent 和节点的构建

```java
LlmRoutingAgent.builder()
    .name("supervisor_agent")
    .model(routingChatModel)
    .state(stateFactory)
    .description(prompt)
    .inputKey("input")
    .outputKey("messages")
    .subAgents(List.of(...))
    .build();
```

### 5.3 策略模式 —— State Key 策略

```java
KeyStrategyFactory stateFactory = () -> {
    HashMap<String, KeyStrategy> map = new HashMap<>();
    map.put("input", new ReplaceStrategy());    // 覆盖
    map.put("messages", new ReplaceStrategy()); // 覆盖
    // 对比：如果是对话场景，messages 会用 AppendStrategy 累积
    return map;
};
```

### 5.4 提示词外置 —— SupervisorAgentPromptConfig

提示词不硬编码在 Java 代码中，而是放在 `application.yml` 中，通过 `@ConfigurationProperties` 绑定，便于修改和动态刷新。

---

## 六、与学习笔记的知识对应

| 你的学习笔记 | 本模块对应内容 |
|-------------|--------------|
| **模块 02 (ChatClient/ChatModel)** | SanitizingRoutingChatModel 实现 ChatModel 接口 |
| **模块 03 (Prompt)** | SupervisorAgentPromptConfig 提示词外置管理 |
| **模块 04 (Structured Output)** | EvaluationClassifierNode 要求 JSON 输出 |
| **模块 06 (Tool Calling)** | CronAgentTools 的 @Tool 注解 |
| **模块 07 (ReActAgent)** | CronTaskParseAgent 是 ReactAgent |
| **模块 08 (Graph)** | DailyReportAgent 和 EvaluationAgent 手写 StateGraph |
| **模块 09 (Parallel)** | IterationNode 的迭代处理（类比 fan-out） |
| **模块 11 (Supervisor)** | LlmRoutingAgent 的 Supervisor 模式 |
| **模块 12 (Four Paradigms)** | 本模块组合了 Supervisor + ReAct + Graph 编排 |
| **模块 15 (ChatClient + Advisor)** | DailyReportAgent 使用 ChatClient + SimpleLoggerAdvisor |
| 🆕 **MCP 协议** | 子 Agent 通过 MCP Server 调用工具（不在本模块） |
| 🆕 **A2A 协议** | A2aRemoteAgent + AgentCardProvider + Nacos |
| 🆕 **定时 Agent** | CompiledGraph.schedule() + XXL-JOB |
| 🆕 **IterationNode** | 逐条 LLM 分析（EvaluationAgent） |

---

## 七、学习建议

### 如果你是第一次看这个模块

1. **先理解架构图**（本文档第 1 节），搞清 Supervisor 在系统中的位置
2. **读 SupervisorAgent.java**，理解 LlmRoutingAgent 的构建过程
3. **读 SupervisorAgentController.java**，理解 SSE 流式输入输出
4. **读 SanitizingRoutingChatModel.java**，理解 LLM 路由输出清洗
5. **再读 AdminAgent.java + CronAgentConfiguration.java**，理解管理端
6. **最后读 DailyReportAgentConfiguration.java**，理解定时 Agent 的 Graph 编排

### 关键思考题

1. 为什么 SupervisorAgent 使用 A2A 远程调用，而 AdminAgent 使用本地调用？
2. SanitizingRoutingChatModel 为什么要先在清洗文本中找路由，找不到再回退到原始文本？
3. DailyReportAgent 为什么用 StateGraph 手写节点，而不是 ReactAgent？
4. EvaluationAgent 的 IterationNode 和模块 09 的 fan-out 有什么区别？

---

> 文档生成日期：2026-07-09
> 覆盖文件：supervisor-agent 模块全部 17 个 Java 文件
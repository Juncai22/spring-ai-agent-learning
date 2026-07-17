# 第一阶段：入口与总览

> 建立整体认知，了解项目结构、模块依赖和启动流程

---

## 1. README.md — 业务场景 + 架构图

### 一句话概括

云边奶茶铺智能助手 Demo，是一个**带记忆的个性化多智能体电商系统**——用户咨询、下单、反馈，系统持续学习用户偏好，实现"越来越懂我"。

### 核心业务流程

```mermaid
flowchart LR
    U[用户] --> A{想做什么?}
    A -->|咨询产品| C[ConsultAgent<br/>RAG 检索 + 个性化推荐]
    A -->|下单| O[OrderAgent<br/>查偏好 + 创建订单]
    A -->|反馈| F[FeedbackAgent<br/>情绪安抚 + 提取偏好]
    C --> M[memory-mcp-server<br/>记录偏好]
    O --> M
    F --> M
```

### 三大核心能力

1. **产品咨询与推荐**：根据用户习惯和喜好推荐奶茶，记录偏好
2. **点单与订单查询**：下单、修改、查询订单，记录下单习惯
3. **反馈与投诉处理**：处理用户反馈，安抚情绪，提取偏好

### 服务架构

```
用户端 (C端):
  前端(3000) → SupervisorAgent(10008) → consult/order/feedback 子Agent

管理端 (B端):
  前端(3000) → AdminAgent(10008) → CronTaskParseAgent → 定时任务

定时任务:
  XXL-JOB → DailyReportAgent(日报) / EvaluationAgent(评价分析) → 钉钉通知
```

---

## 2. pom.xml — 模块依赖关系

### 根 POM 模块结构

```xml
<modules>
    <module>supervisor-agent</module>       <!-- 监督者 Agent -->
    <module>consult-sub-agent</module>      <!-- 咨询子 Agent -->
    <module>order-sub-agent</module>        <!-- 订单子 Agent -->
    <module>feedback-sub-agent</module>     <!-- 反馈子 Agent -->
    <module>order-mcp-server</module>       <!-- 订单 MCP Server -->
    <module>feedback-mcp-server</module>    <!-- 反馈 MCP Server -->
    <module>memory-mcp-server</module>      <!-- 记忆 MCP Server -->
</modules>
```

### 关键版本号

```xml
<properties>
    <spring-ai.version>1.0.0</spring-ai.version>
    <spring-ai-alibaba.version>1.0.0.4</spring-ai-alibaba.version>
    <java.version>17</java.version>
</properties>
```

### 核心依赖关系图

```mermaid
graph TD
    subgraph "根 POM"
        ROOT[pom.xml<br/>spring-ai-alibaba 1.0.0.4]
    end

    subgraph "Agent 模块"
        SA[supervisor-agent]
        CA[consult-sub-agent]
        OA[order-sub-agent]
        FA[feedback-sub-agent]
    end

    subgraph "MCP Server 模块"
        OM[order-mcp-server]
        FM[feedback-mcp-server]
        MM[memory-mcp-server]
    end

    ROOT --> SA
    ROOT --> CA
    ROOT --> OA
    ROOT --> FA
    ROOT --> OM
    ROOT --> FM
    ROOT --> MM

    SA -->|依赖| A2A[spring-ai-alibaba-starter-a2a-client<br/>spring-ai-alibaba-starter-a2a-registry]
    SA -->|依赖| GRAPH[spring-ai-alibaba-graph-core]
    SA -->|依赖| XXL[xxl-job-core]

    CA -->|依赖| A2A_S[spring-ai-alibaba-starter-a2a-server]
    CA -->|依赖| MCP_R[spring-ai-alibaba-starter-mcp-registry]
    CA -->|依赖| DASH[spring-ai-alibaba-starter-dashscope]

    OA -->|依赖| STD_MCP[spring-ai-starter-mcp-client-webflux]
    OA -->|依赖| MCP_R

    OM -->|依赖| MCP_S[spring-ai-starter-mcp-server-webflux]
    OM -->|依赖| MCP_R
```

### 依赖分类

| 依赖类型 | 关键依赖 | 用途 |
|---------|---------|------|
| **A2A 通信** | spring-ai-alibaba-starter-a2a-client/server/registry | Agent 间通信 |
| **MCP 工具** | spring-ai-starter-mcp-server-webflux/client-webflux | 远程工具暴露/发现 |
| **MCP 注册** | spring-ai-alibaba-starter-mcp-registry | Nacos 注册发现 |
| **Graph 引擎** | spring-ai-alibaba-graph-core | Graph 编排 |
| **AI 模型** | spring-ai-starter-model-openai | OpenAI 兼容协议 |
| **RAG** | spring-ai-alibaba-starter-dashscope | 百炼知识库 |
| **调度** | xxl-job-core | 定时任务 |

---

## 3. SupervisorAgentApplication.java — 启动入口

### 代码

```java
@SpringBootApplication
public class SupervisorAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupervisorAgentApplication.class, args);
    }
}
```

### 为什么 SupervisorAgent 是入口

SupervisorAgent 是整个系统的**网关**——它不处理具体业务，只负责路由分发。所有用户请求先到达它，再转发给子 Agent。

### 启动顺序

```mermaid
sequenceDiagram
    participant D as Docker
    participant N as Nacos :8848
    participant MCP as MCP Servers
    participant SUB as 子 Agent
    participant SUP as SupervisorAgent :10008
    participant FE as Frontend :3000

    D->>D: 启动 MySQL, Redis, Nacos
    N->>N: Nacos 就绪

    MCP->>N: order-mcp-server 注册
    MCP->>N: feedback-mcp-server 注册
    MCP->>N: memory-mcp-server 注册

    SUB->>N: consult-sub-agent 注册
    SUB->>N: order-sub-agent 注册
    SUB->>N: feedback-sub-agent 注册

    SUP->>N: supervisor-agent 启动
    SUP->>N: 发现子 Agent 的 AgentCard

    FE->>FE: 前端启动
    FE->>SUP: 连接就绪
```

**关键原则**：SupervisorAgent 必须最后启动，因为它在启动时需要从 Nacos 发现子 Agent——如果子 Agent 还没注册，SupervisorAgent 会打印 WARN 日志，路由功能不可用。

---

## 第一阶段总结

| 学到什么 | 关键概念 |
|---------|---------|
| 项目整体架构 | 7 个微服务模块，分层架构 |
| 模块依赖关系 | A2A 通信、MCP 工具、Graph 引擎、RAG |
| 启动顺序 | MCP Server → 子 Agent → SupervisorAgent → 前端 |
| 核心流程 | 用户请求 → 路由分发 → 业务处理 → 工具调用 → 记忆存储 |

**下一步**：[第二阶段：监督者 Agent](./phase-02-supervisor-agent.md) — 深入理解 LlmRoutingAgent 的路由机制和 A2A 协议。
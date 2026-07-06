# 四范式合一示例：智能内容创作系统

> 同时演示 **ReAct + 并行 + Reflection + Supervisor** 四种 Agent 范式协同工作

## 场景

用户要写一篇技术文章，系统自动完成：查资料 → 写初稿 → 审查 → 修订（循环直到满意）→ 返回终稿。

## 四范式如何体现在代码中

```
┌─────────────────────────────────────────────────────────────┐
│  supervisor_agent  ← 范式④ Supervisor (主 Agent 调度)        │
│  (ReactAgent, 内部 ReAct 循环)  ← 范式① ReAct                │
│                                                             │
│  tools:                                                     │
│    ├─ research_agent (子Agent当工具)                         │
│    │    └─ tools: web_search + knowledge_search              │
│    │       ★ LLM 一次决策同时调两个 → 范式② 并行             │
│    │                                                         │
│    ├─ writer_agent  (子Agent, 写初稿)                        │
│    ├─ critic_agent  (子Agent, 审查)  ← 范式③ Reflection     │
│    └─ reviser_agent (子Agent, 修订)  ← 范式③ Reflection     │
│                                                             │
│  prompt 指导 supervisor 编排:                                │
│    write → critique → (不通过?) revise → critique → ...     │
│    ★ 这个循环就是 范式③ Reflection (质量驱动)                │
└─────────────────────────────────────────────────────────────┘
```

## 完整执行流程

```
用户: "写一篇关于 Spring AI 的文章"
  │
  ▼
supervisor (Supervisor + ReAct)
  │
  ├─① 调 research_agent (子Agent)
  │     └─ research 内部并行调 web_search + knowledge_search  ← 并行
  │        返回: [Web] + [知识库] 资料汇总
  │
  ├─② 调 writer_agent (子Agent)
  │     └─ 根据资料写初稿
  │
  ├─③ 调 critic_agent (子Agent)  ← Reflection 审查
  │     └─ 审查结论: "有问题: 缺少代码示例"
  │
  ├─④ 调 reviser_agent (子Agent) ← Reflection 修订
  │     └─ 修订: 补充代码示例
  │
  ├─⑤ 再调 critic_agent (复查)   ← Reflection 循环
  │     └─ "审查通过"
  │
  └─ 返回终稿
```

## 文件结构

```
four-paradigm-combined-example/
├── pom.xml
└── src/main/
    ├── java/com/alibaba/cloud/ai/example/combined/
    │   ├── CombinedApplication.java          启动类
    │   ├── config/
    │   │   └── CombinedAgentConfig.java      ★★ 四范式合一核心配置
    │   ├── tool/
    │   │   ├── WebSearchTool.java            并行分支1 (联网搜索)
    │   │   └── KnowledgeBaseTool.java        并行分支2 (知识库)
    │   └── controller/
    │       └── CombinedController.java       Web 入口
    └── resources/
        └── application.yml
```

## 关键代码解析（CombinedAgentConfig.java）

### 范式④ Supervisor：子 Agent 当工具

```java
ToolCallback researchTool = AgentTool.getFunctionToolCallback(researchAgent());
ToolCallback writerTool = AgentTool.getFunctionToolCallback(writerAgent());
// 子 Agent 被包装成 ToolCallback, supervisor 像调工具一样调它们

ReactAgent supervisor = ReactAgent.builder()
    .tools(List.of(researchTool, writerTool, criticTool, reviserTool))  // 4个子Agent当工具
    .build();
```

### 范式① ReAct：每个 Agent 内部循环

```java
ReactAgent researchAgent = ReactAgent.builder()
    .tools(List.of(webSearch, knowledgeSearch))  // 有工具 → 内部 ReAct 循环
    .build();
// research 收到主题后: 想要查资料 → 调工具 → 看结果 → 汇总
```

### 范式② 并行：单 Agent 多工具同时调

```java
// research_agent 有两个工具, LLM 一次决策可同时调两个:
.tools(List.of(
    new WebSearchTool().toolCallback(),      // 联网搜索
    new KnowledgeBaseTool().toolCallback())) // 知识库
// LLM 在一次响应中返回两个 tool_calls, 框架并行执行
```

### 范式③ Reflection：审查-修订循环

```java
// supervisor 的 prompt 指导它编排循环:
private static final String SUPERVISOR_PROMPT = """
    ...
    3. 调 critic_agent 审查
    4. 如果 critic 说"审查通过", 结束
    5. 如果有问题, 调 reviser_agent 修订, 然后回到第3步再审查  ← Reflection 循环
    6. 最多重试 3 次, 避免死循环
    ...
    """;
```

## 四范式对照表

| 范式 | 在哪体现 | 关键机制 |
|------|---------|---------|
| ① ReAct | 每个 Agent 是 ReactAgent | 内部「想-做-看」循环 |
| ② 并行 | research_agent 的两个工具 | LLM 一次调多个 tool_calls |
| ③ Reflection | supervisor 编排 critic↔reviser | 审查-修订循环直到满意 |
| ④ Supervisor | supervisor 把子 Agent 当工具 | AgentTool.getFunctionToolCallback |

## 运行方式

```bash
# 1. 配置 DashScope API Key
export AI_DASHSCOPE_API_KEY=your-api-key

# 2. 启动
cd four-paradigm-combined-example
mvn spring-boot:run

# 3. 访问
curl "http://localhost:8088/create?query=写一篇关于 Spring AI 的文章"
```

## 设计要点

1. **范式可组合**：四种范式是正交积木，自由组合
2. **Supervisor 是骨架**：动态调度子 Agent，比 SequentialAgent 灵活
3. **Reflection 靠 prompt**：supervisor 的 prompt 指导它编排审查-修订循环
4. **并行在 Agent 内**：单 Agent 多工具并行是轻量并行（区别于第8站 graph 的跨节点并行）
5. **避免死循环**：prompt 里限制最多重试 3 次

## 与前面模块的关系

| 模块 | 范式 | 本例对应 |
|------|------|---------|
| 第6站 react-agent | ReAct | 每个 Agent 都是 ReactAgent |
| 第8站 parallel-node | 并行 | research_agent 多工具并行 |
| 第9站 llm-auditor | Reflection | critic→reviser 循环 |
| 第10站 subagent | Supervisor | supervisor 调度子 Agent |
| **本例** | **四合一** | **全部组合** |

/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.demo.config;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.a2a.A2aRemoteAgent;
import com.alibaba.cloud.ai.graph.agent.a2a.AgentCardProvider;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.a2a.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;

/**
 * ============================================
 * 监督者智能体 (Supervisor Agent) 配置类
 * ============================================
 *
 * 【核心作用】
 * 这是整个多智能体系统的"大脑"——负责接收用户请求，通过 LLM 分析意图，
 * 然后将请求路由到合适的子智能体（咨询/订单/反馈）去处理。
 *
 * 【架构定位】
 * 在系统架构中，SupervisorAgent 处于"网关层"：
 * 前端 → SupervisorAgent（路由决策） → 子 Agent（业务处理） → MCP Server（工具执行）
 *
 * 【关键概念：LlmRoutingAgent】
 * 这是 Spring AI Alibaba 提供的一个内置 Agent 类型，专门用于"智能路由"场景。
 * 它不同于普通的 ReactAgent，它的核心逻辑是：
 * 1. 接收用户输入
 * 2. 让 LLM 分析意图，决定调用哪个子 Agent
 * 3. 将请求转发给目标子 Agent
 * 4. 把子 Agent 的响应返回给用户
 *
 * LlmRoutingAgent 内部是一个 Graph（图编排），节点结构大致为：
 *   START → preLlm（准备上下文）→ llm（LLM 路由决策）→ a2aNode（调用子 Agent）→ END
 *
 * 【关键概念：A2A 协议（Agent-to-Agent）】
 * 这是 Agent 之间的标准化通信协议。在本项目中：
 * - 子 Agent（consult/order/feedback）作为 A2A Server 注册到 Nacos
 * - SupervisorAgent 作为 A2A Client 通过 Nacos 发现子 Agent
 * - 通信时通过 AgentCard（类似于服务的"名片"）获取子 Agent 的信息
 *
 * 【关键概念：AgentCardProvider】
 * 从 Nacos 服务注册中心获取子 Agent 的 AgentCard。
 * AgentCard 包含子 Agent 的名称、描述、能力、端点等信息。
 * 本项目使用 NacosAgentCardProvider 实现。
 *
 * 【与你学过知识的关联】
 * - 模块 11（subagent-personal-assistant）：Agent as Tool 模式
 *   但这里用的是更高级的 A2A 协议，子 Agent 是远程服务而非本地对象
 * - 模块 12（four-paradigm-combined）：Supervisor 范式
 *   这里也是 Supervisor 模式，但用的是 LlmRoutingAgent 现成实现
 * - 模块 07/08（ReActAgent / Graph）：每个子 Agent 内部是 ReactAgent
 *
 * 【数据流】
 * 用户请求 → Controller → LlmRoutingAgent.stream(input)
 *   → preLlm 节点（准备 state）
 *   → llm 节点（LLM 分析意图，输出子 Agent 名称）
 *   → a2aNode 节点（通过 A2A 协议调用子 Agent，流式返回结果）
 *   → 前端 SSE 展示
 *
 * @see LlmRoutingAgent   Spring AI Alibaba 的智能路由 Agent
 * @see A2aRemoteAgent    A2A 协议的远程 Agent 客户端
 * @see AgentCardProvider 从 Nacos 获取 Agent 信息的提供者
 * @see SanitizingRoutingChatModel 对 LLM 路由输出做清洗的包装器
 */
@Configuration
public class SupervisorAgent {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgent.class);

    /**
     * 注入提示词配置
     * 提示词内容来自 application.yml 中的 agent.prompts.supervisor-agent-instruction
     * 定义了 Supervisor 的角色、可调用的子 Agent 列表、工作流程和约束
     */
    @Autowired
    private SupervisorAgentPromptConfig promptConfig;

    /**
     * ============================================
     * 创建监督者 Agent Bean
     * ============================================
     *
     * 【参数说明】
     * @param chatModel         注入的 ChatModel，使用 @Qualifier("openAiChatModel") 指定
     *                          本项目用的是 OpenAI 兼容协议（可通过环境变量切换模型）
     * @param agentCardProvider 注入的 AgentCardProvider，使用 @Qualifier("nacosAgentCardProvider")
     *                          从 Nacos 注册中心发现子 Agent 的 AgentCard
     *
     * 【构建步骤】
     * 1. 配置 State 策略（ReplaceStrategy：每次更新覆盖旧值）
     * 2. 从 Nacos 获取三个子 Agent 的 AgentCard 并创建 A2aRemoteAgent
     * 3. 用 SanitizingRoutingChatModel 包装 ChatModel（清洗 LLM 路由输出）
     * 4. 构建 LlmRoutingAgent
     *
     * 【State 策略说明】
     * ReplaceStrategy 表示每次更新该 key 时直接覆盖旧值（而非追加）。
     * 这是路由场景的合适选择，因为每次请求的 input/chat_id/user_id 都是新的。
     * 对比：在对话场景中可能用 AppendStrategy 来累积消息历史。
     */
    @Bean
    public LlmRoutingAgent supervisorAgentBean(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Autowired @Qualifier("nacosAgentCardProvider") AgentCardProvider agentCardProvider)
            throws Exception {

        logger.info("agent card provider: {}", agentCardProvider);

        // ============================================
        // 步骤 1：配置 State 的 Key 策略
        // ============================================
        // State 是 Graph 中各节点共享的数据容器
        // KeyStrategy 定义了每个 key 的更新策略：
        //   - ReplaceStrategy：每次更新直接覆盖（适用于路由场景）
        //   - AppendStrategy：追加到已有值（适用于消息历史累积）
        // 这里 4 个 key 都使用 ReplaceStrategy，因为每次请求都是独立的
        KeyStrategyFactory stateFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            // "input"：用户输入文本，每次请求覆盖
            keyStrategyHashMap.put("input", new ReplaceStrategy());
            // "chat_id"：会话 ID，每次请求覆盖
            keyStrategyHashMap.put("chat_id", new ReplaceStrategy());
            // "user_id"：用户 ID，每次请求覆盖
            keyStrategyHashMap.put("user_id", new ReplaceStrategy());
            // "messages"：消息列表，每次请求覆盖（路由场景不需要累积历史）
            keyStrategyHashMap.put("messages", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // ============================================
        // 步骤 2：从 Nacos 获取子 Agent 的 AgentCard 并创建 A2aRemoteAgent
        // ============================================
        // A2aRemoteAgent 是远程 Agent 的本地代理对象
        // 它通过 AgentCardProvider 从 Nacos 获取子 Agent 的地址和元信息
        // 当 LlmRoutingAgent 决定路由到某个子 Agent 时，通过 A2aRemoteAgent 发起远程调用

        // --- 咨询子 Agent ---
        // 负责：产品咨询、活动信息、冲泡指导、产品推荐（含 RAG 知识库检索）
        AgentCard consultAgentCard = agentCardProvider.getAgentCard("consult_agent").getAgentCard();
        if (consultAgentCard != null) {
            logger.info("consult agent card info: {}", consultAgentCard);
        } else {
            logger.warn("consult agent card not found!");  // 子 Agent 未启动时打印警告
        }
        A2aRemoteAgent consultAgent = A2aRemoteAgent.builder()
                .name("consult_agent")                       // 子 Agent 名称，与 Nacos 注册名一致
                .agentCardProvider(agentCardProvider)        // 用于动态获取 AgentCard
                .description("处理奶茶相关产品、活动等咨询问题")  // 描述，LLM 路由时的参考信息
                .build();

        // --- 反馈子 Agent ---
        // 负责：处理用户反馈、投诉安抚、差评处理、偏好提取
        AgentCard feedbackAgentCard = agentCardProvider.getAgentCard("feedback_agent").getAgentCard();
        if (feedbackAgentCard != null) {
            logger.info("feedback agent card info: {}", feedbackAgentCard);
        } else {
            logger.warn("feedback agent card not found!");
        }
        A2aRemoteAgent feedbackAgent = A2aRemoteAgent.builder()
                .name("feedback_agent")
                .agentCardProvider(agentCardProvider)
                .description("云边奶茶铺反馈处理助手")
                .build();

        // --- 订单子 Agent ---
        // 负责：下单、查询订单、修改订单、库存检查、偏好分析
        AgentCard orderAgentCard = agentCardProvider.getAgentCard("order_agent").getAgentCard();
        if (orderAgentCard != null) {
            logger.info("order agent card info: {}", orderAgentCard);
        } else {
            logger.warn("order agent card not found!");
        }
        A2aRemoteAgent orderAgent = A2aRemoteAgent.builder()
                .name("order_agent")
                .agentCardProvider(agentCardProvider)
                .description("云边奶茶铺智能订单处理助手")
                .build();

        logger.info("supervisor_agent initialized with A2A client service");

        try {
            // ============================================
            // 步骤 3：创建路由专用的 ChatModel 包装器
            // ============================================
            // SanitizingRoutingChatModel 的作用：
            // 1. 过滤掉 LLM 输出的  thinking... response 思考块
            // 2. 从 LLM 输出中提取最后一个有效的子 Agent 名称
            //    例如 LLM 输出 "我认为应该调用 consult_agent 来处理"
            //    → 提取出 "consult_agent"
            // 这是容错设计：LLM 可能输出多余的文字，但 LlmRoutingAgent 只需要子 Agent 名称
            ChatModel routingChatModel = new SanitizingRoutingChatModel(
                    chatModel,
                    List.of("consult_agent", "feedback_agent", "order_agent"));

            // ============================================
            // 步骤 4：构建 LlmRoutingAgent
            // ============================================
            return LlmRoutingAgent.builder()
                    .name("supervisor_agent")                  // Agent 名称
                    .model(routingChatModel)                   // 使用清洗后的 ChatModel
                    .state(stateFactory)                       // State 策略
                    .description(promptConfig.getSupervisorAgentInstruction())  // 系统提示词
                    .inputKey("input")                         // 从 state 的哪个 key 读取用户输入
                    .outputKey("messages")                     // 将结果写入 state 的哪个 key
                    .subAgents(List.of(consultAgent, feedbackAgent, orderAgent))  // 可调用的子 Agent 列表
                    .build();
        } catch (Exception e) {
            logger.error("Failed to create LlmRoutingAgent: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize supervisor agent", e);
        }
    }
}
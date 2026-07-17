/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * ============================================
 * 反馈子 Agent 配置类
 * ============================================
 *
 * 【核心作用】
 * 创建云边奶茶铺的"反馈处理助手"智能体，负责：
 * 1. 反馈受理（好评、差评、投诉、建议）
 * 2. 情绪安抚（识别用户情绪，提供差异化安抚策略）
 * 3. 偏好提取（从反馈中提取用户偏好并记录到 Mem0）
 * 4. 解决方案（提供补偿方案，更新处理状态）
 *
 * 【工具来源：单一 MCP 通道（Nacos 发现）】
 * 本 Agent 只使用一个 MCP 通道，通过 Nacos 发现两个 MCP Server：
 *
 * ┌──────────────────────────────────────────────────────┐
 * │              FeedbackAgent 的工具                     │
 * │                                                       │
 * │  ┌────────────────────────────────────────────────┐  │
 * │  │ MCP 通道: Nacos 发现                            │  │
 * │  │ @Qualifier("loadbalancedMcpSyncToolCallbacks")  │  │
 * │  │                                                 │  │
 * │  │ 配置: application.yml                           │  │
 * │  │ alibaba.mcp.nacos.client.sse.connections:      │  │
 * │  │   feedback-mcp-server  → 反馈 CRUD 工具         │  │
 * │  │   memory-mcp-server    → 记忆管理工具           │  │
 * │  └────────────────────────────────────────────────┘  │
 * └──────────────────────────────────────────────────────┘
 *
 * 【与 OrderAgent 的工具加载对比】
 * | 维度         | OrderAgent                    | FeedbackAgent                |
 * |-------------|------------------------------|------------------------------|
 * | MCP 通道数   | 2 个（SSE + Nacos）          | 1 个（Nacos）                |
 * | 为什么不同   | 订单需要直连 + 记忆管理       | 只需要 Nacos 发现            |
 * | 工具来源     | order-mcp-server + memory    | feedback-mcp-server + memory |
 * | 本地工具     | 无                           | 无                           |
 *
 * 【情绪处理策略（来自提示词）】
 * - 愤怒用户：先道歉安抚，再提供解决方案
 * - 失望用户：表示理解，提供补偿方案
 * - 满意用户：表示感谢，记录正面偏好
 * - 建议用户：积极回应，记录改进建议
 *
 * 【三个子 Agent 的对比总结】
 * | 维度       | ConsultAgent      | OrderAgent           | FeedbackAgent       |
 * |-----------|-------------------|----------------------|---------------------|
 * | 本地工具   | ✅ 4个（ConsultTools）| ❌ 无                | ❌ 无               |
 * | MCP 工具   | ✅ memory-server   | ✅ order + memory    | ✅ feedback + memory|
 * | 核心能力   | RAG 检索 + 产品查询 | 订单 CRUD + 记忆     | 反馈处理 + 情绪安抚 |
 * | 共同点     | 全部是 ReactAgent，全部使用 MinimaxCompatibleChatModel，全部通过 A2A 注册 |
 */
@Configuration
public class FeedbackAgent {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackAgent.class);

    @Autowired
    private FeedbackAgentPromptConfig promptConfig;

    ToolCallbackProvider toolsProvider;

    /**
     * 创建反馈子 Agent Bean
     *
     * @param chatModel      注入的 ChatModel
     * @param toolsProvider  注入的 MCP 工具提供者（Nacos 通道）
     *                       连接 feedback-mcp-server 和 memory-mcp-server
     * @return ReactAgent 实例
     */
    @Bean
    public ReactAgent feedbackSubAgentBean(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Autowired(required = false)
            @Qualifier("loadbalancedMcpSyncToolCallbacks")
            ToolCallbackProvider toolsProvider) throws Exception {

        this.toolsProvider = toolsProvider;

        KeyStrategyFactory stateFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            keyStrategyHashMap.put("messages", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // 加载 MCP 工具（feedback-mcp-server + memory-mcp-server）
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
            String toolName = toolCallback.getToolDefinition().name();
            logger.info("feedback_agent add tool: " + toolName);
            tools.add(toolCallback);
        }

        logger.info("feedback_agent add tools: " + tools.size());

        return ReactAgent.builder()
                .name("feedback_agent")
                .model(new MinimaxCompatibleChatModel(chatModel))
                .state(stateFactory)
                .description("用户反馈相关业务处理，支持从反馈中提取和记录用户偏好")
                .instruction(promptConfig.getFeedbackAgentInstruction())
                .inputKey("messages")
                .outputKey("messages")
                .tools(tools)
                .build();
    }
}
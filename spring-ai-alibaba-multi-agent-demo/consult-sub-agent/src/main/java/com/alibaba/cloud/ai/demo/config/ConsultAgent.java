/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import com.alibaba.cloud.ai.agent.nacos.NacosAgentPromptBuilderFactory;
import com.alibaba.cloud.ai.agent.nacos.NacosOptions;
import com.alibaba.cloud.ai.demo.tools.ConsultTools;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.*;

/**
 * ============================================
 * 咨询子 Agent 配置类
 * ============================================
 *
 * 【核心作用】
 * 创建云边奶茶铺的"咨询助手"智能体，负责：
 * 1. 产品咨询与介绍（RAG 知识库检索）
 * 2. 个性化推荐（基于 Mem0 记忆）
 * 3. 用户偏好识别与记录
 *
 * 【Agent 类型：ReactAgent】
 * 这是你学过的模块 07 的标准 ReactAgent（ReAct 范式）。
 * 内部流程：接收消息 → LLM 思考 → 调工具 → 观察结果 → 继续思考... → 最终回复
 *
 * 【工具组合：本地 + MCP 远程】
 * 这是本 Agent 最重要的设计特点——同时使用两种来源的工具：
 *
 * ┌─────────────────────────────────────────────────┐
 * │              ConsultAgent 的工具                 │
 * │                                                  │
 * │  ┌───────────────┐    ┌───────────────────────┐ │
 * │  │ 本地工具 (4个) │    │ MCP 远程工具 (2个)     │ │
 * │  │               │    │                       │ │
 * │  │ searchKnowledge│    │ memory-search (Mem0)  │ │
 * │  │ getProducts   │    │ memory-store (Mem0)   │ │
 * │  │ getProductInfo│    │                       │ │
 * │  │ searchProducts│    │ 来源: memory-mcp-server│ │
 * │  │               │    │ 协议: MCP + Nacos    │ │
 * │  │ 来源: ConsultTools  │                       │ │
 * │  └───────────────┘    └───────────────────────┘ │
 * └─────────────────────────────────────────────────┘
 *
 * 【A2A Server 注册】
 * 本 Agent 同时也是一个 A2A Server（通过 application.yml 配置），
 * 向 Nacos 注册自己的 AgentCard，供 SupervisorAgent 发现和调用。
 *
 * 【MinimaxCompatibleChatModel】
 * 对 ChatModel 做了包装，过滤掉  thinking... response 思考块。
 * 这与 SupervisorAgent 中的 SanitizingRoutingChatModel 类似，但针对流式场景做了优化。
 *
 * 【与 SupervisorAgent 的关系】
 * SupervisorAgent（LlmRoutingAgent）→ A2A 远程调用 → ConsultAgent（本类）
 * 本类作为 A2A Server 被远程调用，内部使用 ReactAgent 处理业务逻辑。
 *
 * @see ConsultTools        本地工具类
 * @see MinimaxCompatibleChatModel  思考块过滤
 * @see ReactAgent          ReAct 范式 Agent
 */
@Configuration
public class ConsultAgent {

    private static final Logger logger = LoggerFactory.getLogger(ConsultAgent.class);

    @Autowired
    private AgentPromptConfig promptConfig;

    @Autowired
    private ConsultTools consultTools;

    /** Nacos 配置选项（服务发现、配置中心） */
    NacosOptions nacosOptions;

    /** MCP 工具提供者（从 Nacos 发现有 loadbalancedMcpSyncToolCallbacks 的 MCP Server） */
    ToolCallbackProvider toolsProvider;

    public ConsultAgent(NacosOptions nacosOptions) {
        this.nacosOptions = nacosOptions;
    }

    /**
     * ============================================
     * 创建咨询子 Agent Bean
     * ============================================
     *
     * @param chatModel      注入的 ChatModel（OpenAI 兼容协议）
     * @param toolsProvider  注入的 MCP 工具提供者（@Qualifier("loadbalancedMcpSyncToolCallbacks")）
     *                       通过 Nacos 发现 memory-mcp-server 并获取其工具
     *                       @Autowired(required = false) 表示 MCP Server 可能未启动
     * @return ReactAgent 实例
     *
     * 【工具组装流程】
     * 1. 从 toolsProvider（Nacos MCP 客户端）获取 MCP 远程工具
     *    → memory-search、memory-store（来自 memory-mcp-server）
     * 2. 从 consultTools（本地 Bean）构建本地工具
     *    → consult-search-knowledge、consult-get-products 等
     * 3. 合并两个来源的工具列表，统一注册到 ReactAgent
     *
     * 【State 策略】
     * 只配置了 "messages" 一个 key，使用 ReplaceStrategy（覆盖策略）。
     * 这是因为 A2A 协议每次调用都是独立的，不需要累积消息历史。
     *
     * 【为什么注释掉了 NacosAgentPromptBuilderFactory】
     * // .builder(new NacosAgentPromptBuilderFactory(nacosOptions))
     * 这行被注释掉，说明当前不使用 Nacos 动态提示词功能。
     * 如果启用，提示词可以从 Nacos 配置中心动态获取，实现运行时修改 Agent 行为。
     */
    @Bean
    public ReactAgent consultSubAgentBean(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Autowired(required = false)
            @Qualifier("loadbalancedMcpSyncToolCallbacks")
            ToolCallbackProvider toolsProvider) throws Exception {

        this.toolsProvider = toolsProvider;

        // State 策略配置
        KeyStrategyFactory stateFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            keyStrategyHashMap.put("messages", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // ============================================
        // 步骤 1：加载 MCP 远程工具
        // ============================================
        // toolsProvider 是 Nacos MCP 客户端的工具回调提供者
        // 它在 application.yml 中配置了要发现的 MCP Server：
        //   spring.ai.alibaba.mcp.nacos.client.sse.connections.memory-mcp-server
        // 会自动从 Nacos 发现 memory-mcp-server 并获取其提供的工具
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
            String toolName = toolCallback.getToolDefinition().name();
            logger.info("consult_agent add mcp tool name: " + toolName);
            tools.add(toolCallback);
        }

        // ============================================
        // 步骤 2：加载本地工具
        // ============================================
        // 使用 MethodToolCallbackProvider 将 @Tool 注解的方法自动包装为 ToolCallback
        // consultTools 中的所有 @Tool 方法会被自动发现和注册
        MethodToolCallbackProvider localToolsProvider = MethodToolCallbackProvider.builder()
                .toolObjects(consultTools)    // 扫描这个对象中所有带 @Tool 注解的方法
                .build();
        for (ToolCallback toolCallback : localToolsProvider.getToolCallbacks()) {
            logger.info("consult_agent add local tool name: " + toolCallback.getToolDefinition().name());
            tools.add(toolCallback);
        }

        logger.info("consult_agent add tools: " + tools.size());
        logger.info("nacos options info: " + nacosOptions.toString());

        // ============================================
        // 步骤 3：构建 ReactAgent
        // ============================================
        return ReactAgent
                // .builder(new NacosAgentPromptBuilderFactory(nacosOptions))  // 动态提示词（未启用）
                .builder()
                .name("consult_agent")                  // Agent 名称（与 A2A AgentCard 的 name 一致）
                .model(new MinimaxCompatibleChatModel(chatModel))  // 包装 ChatModel，过滤思考块
                .state(stateFactory)                    // State 策略
                .description("处理奶茶相关产品、活动等咨询问题，支持基于用户记忆的个性化推荐")
                .instruction(promptConfig.getConsultAgentInstruction())  // 系统提示词
                .inputKey("messages")                   // 从 state 的 "messages" 读取输入
                .outputKey("messages")                  // 将结果写入 state 的 "messages"
                .tools(tools)                           // 注册所有工具（本地 + MCP）
                .build();
    }
}
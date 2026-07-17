/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverEnum;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.RedisSaver;
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
 * 订单子 Agent 配置类
 * ============================================
 *
 * 【核心作用】
 * 创建云边奶茶铺的"订单助手"智能体，负责：
 * 1. 下单处理（创建订单、校验库存、验证产品）
 * 2. 订单查询（按用户ID、订单号、多维度查询）
 * 3. 订单修改/删除（备注修改、订单取消）
 * 4. 个性化推荐（基于 Mem0 记忆的"老样子"下单）
 *
 * 【工具来源：双 MCP 通道】
 * 本 Agent 比 ConsultAgent 更复杂，它同时从两个 MCP 通道获取工具：
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │                  OrderAgent 的工具                        │
 * │                                                           │
 * │  ┌─────────────────────────┐  ┌────────────────────────┐ │
 * │  │ MCP 通道1: SSE 直连      │  │ MCP 通道2: Nacos 发现   │ │
 * │  │ @Qualifier("mcpTool-     │  │ @Qualifier("load-       │ │
 * │  │  Callbacks")             │  │ balancedMcpSyncTool-    │ │
 * │  │                          │  │ Callbacks")             │ │
 * │  │ 配置: application.yml    │  │ 配置: application.yml   │ │
 * │  │ mcp.client.sse           │  │ alibaba.mcp.nacos       │ │
 * │  │ connections:             │  │ client.sse.connections: │ │
 * │  │   order-mcp-server       │  │   memory-mcp-server     │ │
 * │  └─────────────────────────┘  └────────────────────────┘ │
 * └──────────────────────────────────────────────────────────┘
 *
 * 【两个 MCP 通道的区别】
 * - mcpToolCallbacks（SSE直连）：直接通过 SSE 连接 MCP Server，配置简单
 * - loadbalancedMcpSyncToolCallbacks（Nacos发现）：通过 Nacos 服务发现，支持负载均衡
 *
 * 实际上在这个项目中，两个通道都通过 Nacos 配置的（都在 alibaba.mcp.nacos 下），
 * 只是使用了不同的 Bean 名称来区分不同的 MCP Server 连接。
 *
 * 【Saver 注释说明了什么】
 * 被注释掉的 RedisSaver 代码说明设计者考虑了使用 Redis 做 Graph 状态持久化。
 * 当前使用默认的 MemorySaver（内存），重启后状态丢失。
 * 如果启用 RedisSaver，可以支持 Agent 状态持久化和故障恢复。
 *
 */
@Configuration
public class OrderAgent {

    private static final Logger logger = LoggerFactory.getLogger(OrderAgent.class);

    @Autowired
    private OrderAgentPromptConfig promptConfig;

    ToolCallbackProvider toolsProvider;

    /**
     * 创建订单子 Agent Bean
     *
     * @param chatModel          注入的 ChatModel
     * @param toolsProvider      注入的 MCP 工具提供者（SSE 通道，连接 order-mcp-server）
     * @param nacosToolsProvider 注入的 MCP 工具提供者（Nacos 通道，连接 memory-mcp-server）
     * @return ReactAgent 实例
     */
    @Bean
    public ReactAgent orderSubAgentBean(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Autowired(required = false) @Qualifier("mcpToolCallbacks")
            ToolCallbackProvider toolsProvider,
            @Autowired(required = false) @Qualifier("loadbalancedMcpSyncToolCallbacks")
            ToolCallbackProvider nacosToolsProvider) throws Exception {

        this.toolsProvider = toolsProvider;

        // State 策略配置
        KeyStrategyFactory stateFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            keyStrategyHashMap.put("messages", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // ============================================
        // 加载工具：两个 MCP 通道
        // ============================================
        List<ToolCallback> tools = new ArrayList<>();

        // 通道 1：直连 order-mcp-server（订单 CRUD 工具）
        for (ToolCallback toolCallback : toolsProvider.getToolCallbacks()) {
            logger.info("order_agent add tool from sse: " + toolCallback.getToolDefinition().name());
            tools.add(toolCallback);
        }

        // 通道 2：Nacos 发现 memory-mcp-server（记忆管理工具）
        for (ToolCallback toolCallback : nacosToolsProvider.getToolCallbacks()) {
            logger.info("order_agent add tool from nacos: " + toolCallback.getToolDefinition().name());
            tools.add(toolCallback);
        }

        // ============================================
        // 被注释的 Saver 配置（状态持久化，未启用）
        // ============================================
        // var saver = new RedisSaver();
        // var compileConfig = CompileConfig.builder()
        //         .saverConfig(SaverConfig.builder()
        //             .register(SaverEnum.REDIS.getValue(), saver).build())
        //         .build();
        // 如果启用 RedisSaver，Agent 的状态可以持久化到 Redis，
        // 重启后可以恢复未完成的 Graph 执行。

        logger.info("order_agent add tools: " + tools.size());

        return ReactAgent.builder()
                // .compileConfig(compileConfig)   // RedisSaver 编译配置（未启用）
                .name("order_agent")
                .model(new MinimaxCompatibleChatModel(chatModel))
                .state(stateFactory)
                .description("奶茶订单相关业务处理，支持基于用户记忆的智能推荐和下单")
                .instruction(promptConfig.getOrderAgentInstruction())
                .inputKey("messages")
                .outputKey("messages")
                .tools(tools)
                .build();
    }
}
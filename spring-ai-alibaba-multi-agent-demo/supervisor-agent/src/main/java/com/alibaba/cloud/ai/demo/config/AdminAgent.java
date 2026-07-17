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

import java.util.HashMap;
import java.util.List;

import com.alibaba.cloud.ai.demo.config.scheduling.CronAgentConfiguration;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 管理端 Agent 配置类
 * ============================================
 * <p>
 * 【核心作用】
 * 管理端 Agent 用于处理管理员的操作请求，主要功能是：
 * 解析管理员的定时任务指令，创建定时运行的 Agent 任务。
 * 例如管理员说："每天8点帮我生成经营日报"，AdminAgent 会解析并创建定时任务。
 * <p>
 * 【与 SupervisorAgent 的区别】
 * | 维度       | SupervisorAgent                | AdminAgent                       |
 * |-----------|-------------------------------|----------------------------------|
 * | 服务对象   | 普通用户（C端）                | 管理员（B端）                     |
 * | 子 Agent   | consult/order/feedback        | CronTaskParseAgent（仅1个）      |
 * | 子 Agent 类型 | A2aRemoteAgent（远程）      | BaseAgent（本地 ReactAgent）     |
 * | 功能       | 路由到业务子 Agent              | 解析定时任务指令                  |
 * | 输入 key   | "input"                       | "user_query"                     |
 * | 输出 key   | "messages"                    | "agent_input"                    |
 * <p>
 * 【架构设计思考】
 * 为什么 AdminAgent 不用 A2A 远程调用，而是直接使用本地 ReactAgent？
 * 因为 CronTaskParseAgent 是 supervisor-agent 模块内部的一个工具 Agent，
 * 它的职责是解析 cron 表达式并注册定时任务，这直接操作 supervisor-agent 的
 * Spring 容器中的 Bean，不需要独立部署。
 * <p>
 * 而 consult/order/feedback 子 Agent 是独立的微服务，需要通过网络调用，
 * 所以使用 A2A 协议。
 *
 * @see SupervisorAgent 用户端监督者 Agent
 * @see CronAgentConfiguration 定时任务解析 Agent 配置
 */
@Configuration
public class AdminAgent {

    private static final Logger logger = LoggerFactory.getLogger(AdminAgent.class);

    @Autowired
    private SupervisorAgentPromptConfig promptConfig;

    /**
     * 创建管理端 Agent Bean
     *
     * @param chatModel          注入的 ChatModel
     * @param cronTaskParseAgent 注入的定时任务解析 Agent（来自 CronAgentConfiguration）
     *                           这是一个本地 ReactAgent，负责解析 cron 表达式并创建定时任务
     */
    @Bean
    public LlmRoutingAgent adminAgentBean(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Qualifier("cronTaskParseAgent") BaseAgent cronTaskParseAgent) throws Exception {

        // State 策略配置
        KeyStrategyFactory stateFactory = () -> {
            HashMap<String, KeyStrategy> keyStrategyHashMap = new HashMap<>();
            keyStrategyHashMap.put("user_query", new ReplaceStrategy());
            keyStrategyHashMap.put("chat_id", new ReplaceStrategy());
            keyStrategyHashMap.put("user_id", new ReplaceStrategy());
            keyStrategyHashMap.put("result", new ReplaceStrategy());
            return keyStrategyHashMap;
        };

        // 创建路由 ChatModel，只有 CronTaskParseAgent 一个子 Agent
        ChatModel routingChatModel = new SanitizingRoutingChatModel(
                chatModel, List.of("CronTaskParseAgent"));

        // 构建 LlmRoutingAgent
        return LlmRoutingAgent.builder()
                .name("admin_agent")
                .model(routingChatModel)
                .state(stateFactory)
                .description(promptConfig.getSupervisorAgentInstruction())
                .inputKey("user_query")        // 注意：与 SupervisorAgent 不同，这里是 "user_query"
                .outputKey("agent_input")      // 输出到 "agent_input"，子 Agent 从此 key 读取
                .subAgents(List.of(cronTaskParseAgent))  // 只有一个子 Agent
                .build();
    }
}
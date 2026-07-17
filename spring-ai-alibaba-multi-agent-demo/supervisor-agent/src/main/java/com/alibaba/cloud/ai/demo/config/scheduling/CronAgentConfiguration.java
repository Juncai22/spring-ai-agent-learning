/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.util.List;

import com.alibaba.cloud.ai.demo.config.SupervisorAgentPromptConfig;
import com.alibaba.cloud.ai.demo.tools.CronAgentTools;
import com.alibaba.cloud.ai.graph.agent.BaseAgent;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 定时任务解析 Agent 配置
 * ============================================
 *
 * 【核心作用】
 * 创建一个 ReactAgent（CronTaskParseAgent），用于解析管理员的自然语言定时指令，
 * 将其转换为 cron 表达式并创建定时任务。
 *
 * 例如管理员说："每天8点执行经营日报"
 * → CronTaskParseAgent 解析出 cron 表达式 "0 0 8 * * ?"
 * → 调用 CronAgentTools.createCronAgent() 创建定时任务
 *
 * 【Agent 类型选择：为什么用 ReactAgent 而不是 Graph】
 * CronTaskParseAgent 是一个"工具调用型" Agent，它的流程很简单：
 * 1. 接收用户指令
 * 2. 调用工具（createCronAgent）
 * 3. 返回结果
 * 这种简单的"理解→调用工具"场景，ReactAgent 已经足够，不需要手写 Graph。
 *
 * 【与 AdminAgent 的关系】
 * AdminAgent 是 LlmRoutingAgent（路由型），它决定把请求路由给 CronTaskParseAgent。
 * CronTaskParseAgent 是 ReactAgent（工具型），它实际执行定时任务的创建。
 * 两层 Agent 嵌套：AdminAgent（路由）→ CronTaskParseAgent（执行）
 *
 * 【关键依赖：CronAgentTools】
 * CronAgentTools 提供了 createCronAgent() 工具方法，该方法：
 * 1. 从 Spring 容器中查找对应名称的 CompiledGraph Bean
 * 2. 调用 agent.schedule(config) 注册定时任务
 * 3. 返回创建结果
 *
 * @author yaohui
 * @create 2025/9/3 11:46
 **/
@Configuration
public class CronAgentConfiguration {

    @Autowired
    private SupervisorAgentPromptConfig promptConfig;

    /**
     * 创建 CronTaskParseAgent Bean
     *
     * @param cronAgentTools 定时任务工具类（提供 createCronAgent 方法）
     * @param chatModel      注入的 ChatModel
     * @return ReactAgent 实例
     *
     * 【提示词动态组装】
     * schedulingAgentInstruction 模板中包含 %s 占位符，
     * 运行时会被替换为当前容器中可用的定时 Agent 列表。
     * 例如：
     *   "当前可供定时运行的Agent及对应agentName如下：
     *    - AgentName: dailyReportAgent, Function Describe: OperationAnalysisAgent
     *    - AgentName: evaluationAnalysisAgent, Function Describe: ReviewAnalysisAgent"
     */
    @Bean
    public BaseAgent cronTaskParseAgent(
            CronAgentTools cronAgentTools,
            @Qualifier("openAiChatModel") ChatModel chatModel) throws GraphStateException {

        // 获取当前可用的定时 Agent 名称和描述列表
        String agentNames = "";
        for (String desc : cronAgentTools.cronAgentsDesc()) {
            agentNames += "- " + desc + "\n";
        }

        // 将 Agent 列表填入提示词模板的 %s 占位符
        String instruction = String.format(promptConfig.getSchedulingAgentInstruction(), agentNames);

        // 构建 ReactAgent
        ReactAgent cronTaskParseAgent = ReactAgent.builder()
                .name("CronTaskParseAgent")
                .model(chatModel)
                .description("CronTaskParseAgent可按用户提供的定时或周期性执行指令，帮助用户创建一个异步定时运行的Agent.")
                .instruction(instruction)                              // 动态组装的系统提示词
                .inputKey("agent_input")                               // 从 state 的 "agent_input" 读取输入
                .outputKey("messages")                                 // 结果写入 "messages"
                .tools(List.of(ToolCallbacks.from(cronAgentTools)))    // 注册工具：createCronAgent
                .build();

        return cronTaskParseAgent;
    }
}
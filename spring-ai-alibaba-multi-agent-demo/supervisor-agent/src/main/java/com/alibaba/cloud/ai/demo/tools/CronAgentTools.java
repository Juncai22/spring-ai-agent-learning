/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.tools;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.scheduling.ScheduleConfig;
import com.alibaba.cloud.ai.graph.scheduling.ScheduledAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ============================================
 * 定时任务工具类
 * ============================================
 *
 * 【核心作用】
 * 提供 Agent 可调用的定时任务创建工具。
 * 当 CronTaskParseAgent 解析出用户的定时指令后，调用 createCronAgent 方法
 * 来实际创建定时运行的 Agent 任务。
 *
 * 【工具注册方式】
 * 使用 Spring AI 的 @Tool 注解注册工具方法，这是你学过的 4 种方式之一。
 * 与模块 06（tool-calling-example）中的 @Tool 注解用法一致。
 *
 * 【关键概念：CompiledGraph.schedule()】
 * 这是 Spring AI Alibaba Graph 框架的定时任务能力。
 * 每个 CompiledGraph 都可以通过 schedule(config) 方法注册为定时任务。
 * ScheduleConfig 包含 cron 表达式和生命周期监听器。
 *
 * 【定时任务生命周期】
 * 1. CronTaskParseAgent 解析用户指令 → 得到 cron 表达式
 * 2. 调用 createCronAgent(cron, agentName)
 * 3. 从 Spring 容器中查找 CompiledGraph Bean
 * 4. 调用 agent.schedule(ScheduleConfig) 注册定时任务
 * 5. 定时任务由 XXL-JOB 调度执行
 *
 * 【可用的定时 Agent】
 * agentsMap 中包含了所有标记为可定时执行的 CompiledGraph Bean：
 * - dailyReportAgent：经营日报生成
 * - evaluationAnalysisAgent：用户评价分析
 *
 * @author yaohui
 * @create 2025/7/30 22:51
 **/
@Component
public class CronAgentTools {

    private static final Logger logger = LoggerFactory.getLogger(CronAgentTools.class);

    /**
     * 注入所有 CompiledGraph 类型的 Bean
     * 使用 @Autowired(required = false) 是因为 XXL-JOB 可能未启用，此时没有 Agent 注册
     * Map 的 key 是 Bean 名称，value 是 CompiledGraph 实例
     */
    @Autowired(required = false)
    private Map<String, CompiledGraph> agentsMap;

    /**
     * ============================================
     * 创建定时 Agent 任务
     * ============================================
     *
     * 这个方法会被 AI Agent 作为工具调用。
     * 当用户说"每天8点执行经营日报"时：
     * 1. LLM 解析出 cron="0 0 8 * * ?" 和 agentName="dailyReportAgent"
     * 2. LLM 通过 Function Calling 调用本方法
     * 3. 本方法创建定时任务并返回结果
     *
     * @param cron      Quartz 格式的 cron 表达式（6 段），例如 "0 0 8 * * ?" 表示每天8点
     * @param agentName Spring 容器中的 Agent Bean 名称，例如 "dailyReportAgent"
     * @return 创建结果描述
     */
    @Tool(description = "可根据用户提供的定时表达式, 创建运行相应的Agent在后台定时执行")
    public String createCronAgent(
            @ToolParam(description = "Cron expression for scheduling (e.g., '0 0 8 * * ?' for daily at 8 AM，need 6 parameters)")
            String cron,
            @ToolParam(description = "Agent bean name in current spring context")
            String agentName) {

        logger.info("Getting information for {}", cron);
        System.out.println("创建了一个 " + cron + " 的定时Agent。Name:" + agentName);

        // 如果没有可用的 Agent（XXL-JOB 未启用）
        if (agentsMap == null) {
            System.out.println("Agent not found");
            return "Agent not found";
        }

        // 从 Spring 容器中查找对应名称的 Agent
        CompiledGraph agent = agentsMap.get(agentName);
        if (agent == null) {
            System.out.println("Agent not found");
            return "Agent not found";
        }

        // 创建定时任务配置
        ScheduleConfig config = ScheduleConfig.builder()
                .cronExpression(cron)    // 设置 cron 表达式
                .build();

        // 注册定时任务
        ScheduledAgentTask task = agent.schedule(config);

        return "成功创建了一个 " + cron + " 的定时Agent。" + agent.stateGraph.getName();
    }

    /**
     * 获取所有可用的定时 Agent 描述列表
     * 用于生成 CronTaskParseAgent 的系统提示词，让 LLM 知道有哪些 Agent 可以定时运行
     *
     * @return Agent 描述列表，格式：["AgentName: dailyReportAgent, Function Describe: OperationAnalysisAgent", ...]
     */
    public List<String> cronAgentsDesc() {
        if (agentsMap == null) {
            return List.of();
        }
        return agentsMap.entrySet().stream()
                .map(entry -> "AgentName: " + entry.getKey()
                        + ", Function Describe: " + entry.getValue().stateGraph.getName())
                .toList();
    }
}
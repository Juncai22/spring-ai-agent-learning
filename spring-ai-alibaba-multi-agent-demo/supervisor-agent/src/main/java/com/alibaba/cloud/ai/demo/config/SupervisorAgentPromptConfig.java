/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 监督者 Agent 提示词配置类
 * ============================================
 *
 * 【核心作用】
 * 从 application.yml 中读取 Agent 的系统提示词配置。
 * 使用 Spring Boot 的 @ConfigurationProperties 机制，自动绑定到 agent.prompts 前缀的配置。
 *
 * 【为什么要把提示词外置到配置文件】
 * 1. 易于修改：不需要重新编译就能调整 Agent 的行为
 * 2. 环境区分：不同环境（开发/测试/生产）可以使用不同的提示词
 * 3. 动态刷新：配合 Nacos 配置中心，可以实现运行时动态修改提示词
 * 4. 可维护性：提示词可能很长，放在配置文件中比硬编码在代码里更清晰
 *
 * 【配置绑定】
 * application.yml 中：
 *   agent:
 *     prompts:
 *       supervisor-agent-instruction: |
 *         角色与职责: ...
 *       scheduling-agent-instruction: |
 *         角色与职责: ...
 *
 * 对应的 Java 属性：
 *   supervisorAgentInstruction = "角色与职责: ..."
 *   schedulingAgentInstruction = "角色与职责: ..."
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class SupervisorAgentPromptConfig {

    /**
     * 监督者 Agent 的系统提示词
     * 包含：角色定义、可调用的子 Agent 列表、工作流程、约束条件
     */
    private String supervisorAgentInstruction;

    /**
     * 定时任务解析 Agent 的系统提示词
     * 包含：角色定义、可用的定时 Agent 列表、cron 表达式解析规则、约束条件
     * 注意：这个提示词包含 %s 占位符，运行时会被替换为实际的 Agent 名称列表
     */
    private String schedulingAgentInstruction;

    public String getSupervisorAgentInstruction() {
        return supervisorAgentInstruction;
    }

    public void setSupervisorAgentInstruction(String supervisorAgentInstruction) {
        this.supervisorAgentInstruction = supervisorAgentInstruction;
    }

    public String getSchedulingAgentInstruction() {
        return schedulingAgentInstruction;
    }

    public void setSchedulingAgentInstruction(String schedulingAgentInstruction) {
        this.schedulingAgentInstruction = schedulingAgentInstruction;
    }
}
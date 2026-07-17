/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 咨询 Agent 提示词配置类
 * ============================================
 *
 * 从 application.yml 的 agent.prompts 前缀读取配置。
 * 提示词定义了 Agent 的角色、核心能力、工作流程、个性化策略和约束。
 *
 * 关键提示词策略：
 * 1. 每次回答前先查询用户记忆（memory-search）
 * 2. 结合偏好提供个性化推荐
 * 3. 识别偏好变化并及时更新记忆（memory-store）
 * 4. 记忆操作对用户透明（不告知用户"我正在记录你的偏好"）
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class AgentPromptConfig {

    private String consultAgentInstruction;

    public String getConsultAgentInstruction() {
        return consultAgentInstruction;
    }

    public void setConsultAgentInstruction(String consultAgentInstruction) {
        this.consultAgentInstruction = consultAgentInstruction;
    }
}
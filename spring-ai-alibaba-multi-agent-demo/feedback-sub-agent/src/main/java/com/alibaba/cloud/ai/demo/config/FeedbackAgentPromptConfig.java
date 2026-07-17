/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 反馈 Agent 提示词配置类
 * ============================================
 *
 * 提示词定义了反馈 Agent 的核心行为：
 * 1. 识别用户情绪，提供差异化安抚策略
 * 2. 从反馈中提取偏好（正面/负面/建议）
 * 3. 记录反馈和解决方案
 * 4. 不越界处理订单和咨询问题
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class FeedbackAgentPromptConfig {

    private String feedbackAgentInstruction;

    public String getFeedbackAgentInstruction() {
        return feedbackAgentInstruction;
    }

    public void setFeedbackAgentInstruction(String feedbackAgentInstruction) {
        this.feedbackAgentInstruction = feedbackAgentInstruction;
    }
}
/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================
 * 订单 Agent 提示词配置类
 * ============================================
 *
 * 提示词定义了订单 Agent 的核心行为：
 * 1. 下单前查询用户记忆，提供个性化推荐
 * 2. 支持"老样子"下单（查询历史订单）
 * 3. 自动填充甜度/冰量偏好
 * 4. 严格的安全约束：只能操作自己的订单
 */
@Configuration
@ConfigurationProperties(prefix = "agent.prompts")
public class OrderAgentPromptConfig {

    private String orderAgentInstruction;

    public String getOrderAgentInstruction() {
        return orderAgentInstruction;
    }

    public void setOrderAgentInstruction(String orderAgentInstruction) {
        this.orderAgentInstruction = orderAgentInstruction;
    }
}
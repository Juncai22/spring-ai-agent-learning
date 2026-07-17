/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================
 * 监督者 Agent 应用启动类
 * ============================================
 *
 * 【运行端口】
 * 10008（在 application.yml 中配置 server.port=10008）
 *
 * 【依赖的基础设施】
 * - Nacos（服务注册与发现，端口 8848）：用于发现子 Agent 的 A2A 服务
 * - MySQL（数据库，端口 3306）：用于定时任务读取订单和反馈数据
 * - Redis（缓存，端口 6379）：（如果启用）用于状态存储
 *
 * 【启动顺序】
 * 在系统启动流程中，SupervisorAgent 是最后启动的：
 * 1. MySQL / Nacos / Redis（Docker 中间件）
 * 2. MCP Server（order-mcp-server、feedback-mcp-server、memory-mcp-server）
 * 3. 子 Agent（consult-sub-agent、order-sub-agent、feedback-sub-agent）
 * 4. SupervisorAgent ← 本应用（最后启动，因为它依赖子 Agent 已注册到 Nacos）
 */
@SpringBootApplication
public class SupervisorAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupervisorAgentApplication.class, args);
    }
}
/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo;

import com.alibaba.cloud.ai.demo.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ============================================
 * 记忆 MCP 工具类
 * ============================================
 *
 * 【核心作用】
 * 通过 MCP 协议对外暴露 2 个记忆管理工具，供所有子 Agent 远程调用。
 * 底层对接 Mem0 API（https://api.mem0.ai），实现用户长期偏好记忆。
 *
 * 【工具清单】
 * ┌──────────────────────────────────────────────────────────┐
 * │ ① memory-search  检索用户历史偏好（查）                   │
 * │ ② memory-store   存储用户新偏好（存，异步执行）           │
 * └──────────────────────────────────────────────────────────┘
 *
 * 【为什么记忆管理要独立为 MCP Server】
 * 三个子 Agent（consult/order/feedback）都需要记忆能力：
 * - ConsultAgent：查询用户偏好来个性化推荐
 * - OrderAgent：查询用户历史订单习惯，下单后记录新偏好
 * - FeedbackAgent：从用户反馈中提取偏好并记录
 *
 * 如果每个 Agent 都自己连 Mem0，会有三个问题：
 * 1. Mem0 API Key 分散在多个服务中（安全风险）
 * 2. 记忆格式不统一（consistency 问题）
 * 3. 升级 Mem0 逻辑需要改 3 个服务（维护成本）
 *
 * 独立为 MCP Server 后，三个 Agent 通过统一的 MCP 接口访问，
 * 记忆逻辑集中管理，格式统一，API Key 只保存在一个地方。
 *
 * 【记忆存储的维度】
 * MemoryService 支持存储多维度用户偏好：
 * - 产品偏好：喜欢/不喜欢的产品
 * - 口味偏好：甜度、冰量、口味
 * - 服务偏好：配送、包装、服务态度
 * - 消费习惯：价格敏感度、促销响应、下单时间
 * - 情绪反馈：满意度、投诉内容、建议
 *
 * 【与 ChatMemory（模块 05）的区别】
 * | 维度       | ChatMemory           | Mem0 (本类)          |
 * |-----------|---------------------|----------------------|
 * | 记忆内容   | 对话历史             | 用户偏好画像          |
 * | 记忆时长   | 短期（会话级）        | 长期（跨会话）        |
 * | 存储位置   | 本地内存/数据库      | Mem0 云端服务         |
 * | 检索方式   | 按会话ID            | 语义检索（向量相似度）|
 * | 用途       | 多轮对话上下文       | 个性化推荐            |
 */
@Service
public class MemoryMcpTools {

    private static final Logger logger = LoggerFactory.getLogger(MemoryMcpTools.class);

    @Autowired
    private MemoryService memoryService;

    /**
     * 存储用户记忆（异步执行）
     *
     * ★ 设计亮点：异步存储
     * 调用后立即返回"成功"，不等待 Mem0 API 响应。
     * 因为记忆存储不是用户对话的关键路径，异步执行可以：
     * 1. 不阻塞用户对话（用户体验更好）
     * 2. 容错（Mem0 服务挂了不影响正常对话）
     * 3. 削峰（大量并发写入时不会拖垮 Agent）
     *
     * @param userId  用户唯一标识
     * @param content 偏好描述，如"用户喜欢半糖去冰的茉莉花茶"
     * @return 立即返回存储结果
     */
    @Tool(name = "memory-store",
          description = "存储用户的多维度偏好和习惯信息，包括产品偏好、口味偏好、服务偏好、消费习惯、情绪反馈等，为个性化推荐、智能咨询、订单处理提供基础数据支持")
    public String storeMemory(
            @ToolParam(description = "用户唯一标识符，用于关联用户的所有记忆信息") String userId,
            @ToolParam(description = "用户偏好和习惯的详细描述，包括：产品偏好（喜欢/不喜欢的产品）、口味偏好（甜度、冰量、口味）、服务偏好（配送、包装、服务态度）、消费习惯（价格敏感度、促销响应、下单时间）、情绪反馈（满意度、投诉内容、建议）等") String content) {
        return memoryService.storeMemory(userId, content);
    }

    /**
     * 检索用户历史记忆
     *
     * ★ 检索策略：时间范围限定 + 语义匹配
     * 1. 时间范围：默认搜索最近两周的记忆（避免返回过时偏好）
     * 2. 语义匹配：使用向量相似度检索，而非关键词匹配
     *    例如 query="甜度偏好" 能匹配到 "用户喜欢半糖口味" 的记忆
     *
     * Agent 使用指南：
     * - 每次回答用户前先调用此工具
     * - query 参数使用具体的偏好类型，如"甜度偏好"、"产品偏好"
     * - 结果为空时说明该用户是新用户或没有相关记忆
     *
     * @param userId 用户唯一标识
     * @param query  检索查询语句，例如"甜度偏好"、"产品偏好"、"下单习惯"
     * @return 匹配的记忆文本，多条记忆用换行分隔
     */
    @Tool(name = "memory-search",
          description = "检索用户的历史偏好、习惯和反馈信息，支持个性化推荐、智能咨询、订单处理等场景，可查询产品偏好、口味偏好、服务偏好、消费习惯、情绪反馈等多维度信息")
    public String searchMemory(
            @ToolParam(description = "用户唯一标识符，用于检索该用户的所有记忆信息") String userId,
            @ToolParam(description = "检索查询语句，可以是具体的偏好类型（如'甜度偏好'、'产品偏好'）、产品名称（如'奶茶'、'咖啡'）、行为模式（如'下单习惯'、'消费习惯'）或情感关键词（如'喜欢'、'不喜欢'）") String query) {
        return memoryService.searchMemory(userId, query);
    }
}
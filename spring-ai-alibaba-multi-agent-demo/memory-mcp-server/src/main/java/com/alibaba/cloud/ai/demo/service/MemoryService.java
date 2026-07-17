/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.service;

import com.alibaba.cloud.ai.demo.config.Mem0Config;
import com.alibaba.cloud.ai.demo.dto.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ============================================
 * Mem0 记忆服务
 * ============================================
 *
 * 【核心作用】
 * 封装 Mem0 API（https://api.mem0.ai）的调用逻辑，提供记忆的存储和检索功能。
 *
 * 【Mem0 是什么】
 * Mem0 是一个专门为 AI Agent 设计的记忆管理服务，核心能力：
 * - 语义记忆：不是简单的关键词匹配，而是基于向量相似度的语义检索
 * - 多维度：支持产品偏好、口味偏好、消费习惯等多个维度
 * - 自动去重：相同含义的记忆不会重复存储
 * - 时间衰减：过时的记忆会自动降低权重
 *
 * 【API 设计】
 * Mem0 提供两套 API：
 * - V1 (POST /v1/memories/)：创建记忆，支持批量消息
 * - V2 (POST /v2/memories/search/)：语义检索，支持过滤条件
 *
 * 【关键设计 1：异步存储（storeMemoryAsync）】
 * 存储记忆是异步执行的，调用后立即返回"成功"。
 * 为什么异步？
 * 1. 存储不是用户对话的关键路径（用户不需要等记忆存完）
 * 2. Mem0 服务可能响应慢（网络延迟），异步避免阻塞 Agent
 * 3. 容错：Mem0 挂了不影响正常对话，记忆会在后台重试
 *
 * 实现方式：
 * - storeMemory() 同步返回
 * - 通过 ApplicationContext.getBean() 获取代理对象
 * - 调用 @Async 标记的 storeMemoryAsync() 在独立线程池执行
 * - 线程池配置见 AsyncConfig（核心 2 线程，最大 5 线程）
 *
 * 【关键设计 2：时间范围过滤（searchMemory）】
 * 搜索只返回最近两周的记忆。
 * 为什么？
 * 1. 用户的偏好可能随时间变化（夏天喜欢冰的，冬天喜欢热的）
 * 2. 避免返回过多不相关记忆，提高检索精度
 * 3. 减少 Mem0 API 的检索开销
 *
 * 【关键设计 3：ApplicationContext 获取代理】
 * 为什么用 applicationContext.getBean(MemoryService.class) 而不是直接 this.storeMemoryAsync()？
 * 因为 Spring 的 @Async 注解依赖 AOP 代理，直接调用 this 会绕过代理，
 * 导致异步注解失效。通过 getBean 获取代理对象再调用，确保异步生效。
 */
@Service
public class MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryService.class);

    /** Mem0 V1 API：创建记忆 */
    private static final String MEMORIES_URI_V1 = "/v1/memories/";

    /** Mem0 V2 API：语义检索记忆 */
    private static final String MEMORIES_URI_V2 = "/v2/memories/search/";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Mem0Config config;
    private final ApplicationContext applicationContext;

    @Autowired
    public MemoryService(RestTemplate restTemplate, Mem0Config config,
                          ApplicationContext applicationContext) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
        this.config = config;
        this.applicationContext = applicationContext;
    }

    /**
     * ============================================
     * 检索用户记忆（V2 API）
     * ============================================
     *
     * 使用 Mem0 的语义检索能力，根据查询语句找到最匹配的用户记忆。
     *
     * 请求示例：
     * POST /v2/memories/search/
     * {
     *   "query": "甜度偏好",
     *   "filters": {
     *     "AND": [
     *       {"user_id": "10001"},
     *       {"created_at": {"gte": "2026-06-26", "lte": "2026-07-11"}}
     *     ]
     *   }
     * }
     *
     * 响应示例：
     * [
     *   {"memory": "用户喜欢半糖口味，偏好茉莉花茶底"},
     *   {"memory": "用户不喜欢过于甜腻的饮品"}
     * ]
     *
     * @param userId 用户唯一标识
     * @param query  语义检索查询
     * @return 匹配的记忆文本，多条用换行分隔；未找到返回"未找到用户历史喜好"
     */
    public String searchMemory(String userId, String query) {
        try {
            // 计算时间范围：从两周前到明天
            LocalDate today = LocalDate.now();
            LocalDate twoWeeksAgo = today.minusWeeks(2);
            LocalDate tomorrow = today.plusDays(1);

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            String startDate = twoWeeksAgo.format(formatter);
            String endDate = tomorrow.format(formatter);

            // 构建请求体：filters + query
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> filters = new HashMap<>();
            List<Map<String, Object>> andConditions = new ArrayList<>();

            // 条件 1：用户ID过滤
            Map<String, Object> userIdCondition = new HashMap<>();
            userIdCondition.put("user_id", userId);
            andConditions.add(userIdCondition);

            // 条件 2：时间范围过滤（gte=大于等于, lte=小于等于）
            Map<String, Object> timeCondition = new HashMap<>();
            Map<String, String> createdAtRange = new HashMap<>();
            createdAtRange.put("gte", startDate);
            createdAtRange.put("lte", endDate);
            timeCondition.put("created_at", createdAtRange);
            andConditions.add(timeCondition);

            filters.put("AND", andConditions);  // 两个条件 AND 组合
            requestBody.put("filters", filters);
            requestBody.put("query", query);    // 语义检索查询

            String requestJson = objectMapper.writeValueAsString(requestBody);
            logger.info("Sending memory search request: {}", requestJson);

            // 构建 HTTP 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + config.getApi().getKey());
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            String url = config.getApi().getUrl() + MEMORIES_URI_V2;
            ResponseEntity<String> responseEntity =
                    restTemplate.postForEntity(url, requestEntity, String.class);
            String response = responseEntity.getBody();

            // 解析响应：提取每条记忆的 "memory" 字段
            List<Map<String, Object>> memories = objectMapper.readValue(
                    response, new TypeReference<List<Map<String, Object>>>() {});

            if (!memories.isEmpty()) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < memories.size(); i++) {
                    Map<String, Object> memory = memories.get(i);
                    result.append(memory.get("memory"));  // 提取记忆文本
                    if (i < memories.size() - 1) {
                        result.append("\n");  // 多条记忆用换行分隔
                    }
                }
                logger.info("Found {} memories for user: {} in date range {} to {}",
                        memories.size(), userId, startDate, endDate);
                return result.toString();
            } else {
                logger.warn("No memories found for user: {} in date range {} to {}",
                        userId, startDate, endDate);
                return "未找到用户历史喜好";
            }
        } catch (Exception e) {
            logger.error("Error searching memories for user: {}", userId, e);
            return "未找到用户历史喜好";
        }
    }

    /**
     * ============================================
     * 存储用户记忆（同步入口 → 异步执行）
     * ============================================
     *
     * 这是同步入口方法，被 MemoryMcpTools.storeMemory() 调用。
     * 内部立即返回"成功"，然后通过代理对象异步执行实际存储。
     *
     * @param userId  用户唯一标识
     * @param content 偏好描述
     * @return 立即返回"成功存储用户喜好"
     */
    public String storeMemory(String userId, String content) {
        logger.info("Memory storage request received for user: {}, content: {}", userId, content);

        // ★ 通过 ApplicationContext 获取代理对象，确保 @Async 生效
        // 不能直接 this.storeMemoryAsync()，因为会绕过 Spring AOP 代理
        MemoryService self = applicationContext.getBean(MemoryService.class);
        self.storeMemoryAsync(userId, content);

        return "成功存储用户喜好";  // 立即返回，不等待异步完成
    }

    /**
     * ============================================
     * 异步存储用户记忆（V1 API）
     * ============================================
     *
     * @Async("memoryTaskExecutor") 指定使用专用线程池，避免占用主线程。
     * 线程池配置：核心 2 线程，最大 5 线程，队列容量 100。
     *
     * 请求示例：
     * POST /v1/memories/
     * {
     *   "messages": [{"role": "user", "content": "用户喜欢半糖去冰的茉莉花茶"}],
     *   "user_id": "10001"
     * }
     *
     * @param userId  用户唯一标识
     * @param content 偏好描述
     */
    @Async("memoryTaskExecutor")
    public void storeMemoryAsync(String userId, String content) {
        try {
            logger.info("Starting async memory storage for user: {}", userId);

            // 构建消息
            Message message = new Message("user", content);
            List<Message> messages = Arrays.asList(message);

            // 构建 V1 API 请求体
            Mem0ServerRequest.MemoryCreate memoryCreate = Mem0ServerRequest.MemoryCreate.builder()
                    .messages(messages)
                    .userId(userId != null && !userId.trim().isEmpty()
                            ? userId : "default_user")
                    .build();

            String requestJson = objectMapper.writeValueAsString(memoryCreate);
            logger.info("Sending async memory request: {}", requestJson);

            // 发送 HTTP 请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + config.getApi().getKey());
            HttpEntity<String> requestEntity = new HttpEntity<>(requestJson, headers);

            String url = config.getApi().getUrl() + MEMORIES_URI_V1;
            ResponseEntity<String> responseEntity =
                    restTemplate.postForEntity(url, requestEntity, String.class);
            String response = responseEntity.getBody();

            if (response != null) {
                logger.info("Successfully added memory with {} messages for user: {}",
                        memoryCreate.getMessages().size(), userId);
                logger.debug("Memory creation response: {}", response);
            }

            logger.info("Async memory storage completed successfully for user: {}", userId);
        } catch (Exception e) {
            // 异步存储失败不影响主流程，只记录日志
            logger.error("Error in async memory storage for user: {}", userId, e);
        }
    }
}
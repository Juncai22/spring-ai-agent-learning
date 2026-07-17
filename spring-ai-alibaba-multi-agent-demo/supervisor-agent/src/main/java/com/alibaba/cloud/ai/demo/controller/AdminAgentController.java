/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * ...
 */

package com.alibaba.cloud.ai.demo.controller;

import java.util.Map;
import java.util.concurrent.CompletionException;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ============================================
 * 管理端 Agent 控制器
 * ============================================
 *
 * 【核心职责】
 * 对外暴露管理端 API，接收管理员的操作请求（如创建定时任务），
 * 转发给 AdminAgent 处理，通过 SSE 流式返回结果。
 *
 * 【API 端点】
 * GET /api/admin/chat?chat_id={会话ID}&user_query={管理指令}
 *
 * 【使用示例】
 * curl "http://localhost:10001/api/admin/chat?chat_id=1&user_id=123&user_query=每2分钟帮我统计下评价数据分析"
 *
 * 【与 SupervisorAgentController 的区别】
 * | 维度             | SupervisorAgentController | AdminAgentController        |
 * |-----------------|--------------------------|----------------------------|
 * | 路径             | /api/assistant/chat       | /api/admin/chat             |
 * | 参数             | chat_id, user_query, user_id | chat_id, user_query       |
 * | 不需要 user_id   | 需要（C端用户标识）        | 不需要（管理员操作）         |
 * | 流式执行方式     | getAndCompileGraph().fluxStream | agent.stream(input)    |
 * | 过滤逻辑         | 过滤 "Agent State: submitted" | 不过滤（宽松）              |
 *
 * 【为什么 stream 方式不同】
 * SupervisorAgentController 使用 getAndCompileGraph().fluxStream() 手动控制，
 * 这是为了更精细地控制流式输出（过滤特定状态消息）。
 * AdminAgentController 直接使用 agent.stream() 简化版 API，
 * 因为管理端场景对输出控制的要求更宽松。
 */
@RequestMapping("/api/admin")
@RestController
public class AdminAgentController {

    private static final Logger logger = LoggerFactory.getLogger(AdminAgentController.class);
    private final LlmRoutingAgent adminAgent;

    public AdminAgentController(@Qualifier("adminAgentBean") LlmRoutingAgent adminAgent) {
        this.adminAgent = adminAgent;
    }

    /**
     * 管理端对话接口（SSE 流式输出）
     *
     * @param chatID    会话 ID
     * @param userQuery 管理员指令，例如 "每2分钟帮我统计下评价数据分析"
     * @return SSE 流
     *
     * 【典型流程】
     * 1. 管理员输入："每天8点执行经营日报"
     * 2. AdminAgent 路由到 CronTaskParseAgent
     * 3. CronTaskParseAgent 解析出 cron 表达式 "0 0 8 * * ?"
     * 4. 调用 createCronAgent 工具创建定时任务
     * 5. 返回创建结果
     */
    @RequestMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam(name = "chat_id") String chatID,
            @RequestParam(name = "user_query") String userQuery) throws Exception {

        // 构建输入（不需要 user_id，因为这是管理端操作）
        Map<String, Object> input = Map.of("chat_id", chatID, "user_query", userQuery);

        // 创建 SSE 管道
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 使用 agent.stream() 简化版 API 进行流式执行
        Flux<NodeOutput> result = adminAgent.stream(input);

        // 处理流式输出
        processStream(result, sink);

        return sink.asFlux()
                .doOnCancel(() -> logger.info("Client disconnected from stream"))
                .doOnError(e -> logger.error("Error occurred during streaming", e));
    }

    /**
     * 流式输出处理
     * 与 SupervisorAgentController.processStream 类似，但不过滤特定状态消息
     */
    public void processStream(Flux<NodeOutput> generator, Sinks.Many<ServerSentEvent<String>> sink) {
        generator
                .doOnNext(output -> logger.info("output = {}", output))
                .filter(output -> "a2aNode".equals(output.node()) && output instanceof StreamingOutput)
                .cast(StreamingOutput.class)
                .map(StreamingOutput::chunk)
                .filter(content -> content != null && !content.isEmpty())  // 不过滤 "Agent State: submitted"
                .map(content -> ServerSentEvent.builder(content).build())
                .doOnNext(sink::tryEmitNext)
                .doOnError(e -> {
                    logger.error("Unexpected error in stream processing: {}", e.getMessage(), e);
                    sink.tryEmitNext(ServerSentEvent.builder("系统处理出现错误，请稍后重试。").build());
                })
                .doOnComplete(() -> {
                    logger.info("Stream processing completed successfully");
                    sink.tryEmitComplete();
                })
                .subscribe(
                        null,
                        e -> {
                            logger.error("Stream processing failed: {}", e.getMessage(), e);
                            sink.tryEmitError(e);
                        }
                );
    }
}
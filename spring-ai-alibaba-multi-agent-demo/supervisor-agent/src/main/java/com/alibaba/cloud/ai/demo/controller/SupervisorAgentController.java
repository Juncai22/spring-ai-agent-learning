/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.demo.controller;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.flow.agent.LlmRoutingAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;

/**
 * ============================================
 * 监督者 Agent 控制器
 * ============================================
 *
 * 【核心职责】
 * 对外暴露 HTTP API，接收前端的用户请求，转发给 SupervisorAgent 处理，
 * 并将结果以 SSE（Server-Sent Events）流式推送给前端。
 *
 * 【API 端点】
 * GET /api/assistant/chat?chat_id={会话ID}&user_query={用户输入}&user_id={用户ID}
 *
 * 【为什么用 SSE 而不是 WebSocket 或普通 HTTP】
 * SSE（Server-Sent Events）是单向流（服务器→客户端），适合 AI 对话的流式输出场景：
 * - 用户发一条消息，AI 逐字返回
 * - 不需要双向通信（WebSocket 的开销更大）
 * - 浏览器原生支持 EventSource API
 *
 * 【关键概念：Sinks.Many】
 * Sinks 是 Project Reactor 中的"可编程生产者"。
 * 这里用来手动控制 SSE 流的生命周期：
 * - sink.tryEmitNext()：发送一条 SSE 事件
 * - sink.tryEmitComplete()：关闭流
 * - sink.tryEmitError()：发送错误
 *
 * 【关键概念：CompiledGraph.fluxStream()】
 * 通过编译后的 Graph 获取流式执行结果。
 * Graph 内部有多个节点，每个节点执行完会产出一个 NodeOutput。
 * 这里只关心 "a2aNode" 节点的输出（即子 Agent 的流式返回）。
 *
 * 【数据流】
 * 前端（SSE EventSource） → Controller.chat()
 *   → supervisorAgent.getAndCompileGraph().fluxStream(input, config)
 *   → Flux<NodeOutput>（包含所有节点的输出）
 *   → processStream() 过滤只保留 a2aNode 的 StreamingOutput
 *   → 逐条包装成 ServerSentEvent 推送前端
 *
 * 【与 AdminAgentController 的区别】
 * - 这个 Controller 处理用户端请求（咨询、下单、反馈）
 * - AdminAgentController 处理管理端请求（定时任务配置等）
 *
 * @see AdminAgentController 管理端控制器
 * @see LlmRoutingAgent   核心路由 Agent
 */
@RequestMapping("/api/assistant/")
@RestController
public class SupervisorAgentController {

    private static final Logger logger = LoggerFactory.getLogger(SupervisorAgentController.class);

    /**
     * 注入 SupervisorAgent Bean
     * 通过 @Qualifier 指定 Bean 名称为 "supervisorAgentBean"
     */
    private final LlmRoutingAgent supervisorAgent;

    public SupervisorAgentController(@Qualifier("supervisorAgentBean") LlmRoutingAgent supervisorAgent) {
        this.supervisorAgent = supervisorAgent;
    }

    /**
     * ============================================
     * 用户对话接口（SSE 流式输出）
     * ============================================
     *
     * @param chatID    会话ID，用于标识一个对话会话
     * @param userQuery 用户输入的文本
     * @param userID    用户ID，用于个性化推荐和记忆管理
     * @return SSE 流，逐条推送 AI 响应
     *
     * 【设计细节：userID 嵌入 userQuery】
     * 在 userQuery 后面追加了 XML 标签 <userId>xxx</userId>，
     * 这样 userID 被嵌入到 input 文本中，子 Agent 可以从中提取用户 ID。
     * 这是一种简单的"带外数据内联"模式，避免修改 A2A 协议的 input 结构。
     */
    @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(
            @RequestParam(name = "chat_id") String chatID,
            @RequestParam(name = "user_query") String userQuery,
            @RequestParam(name = "user_id") String userID) throws Exception {

        logger.info("Received user query: {}", userQuery);

        try {
            // ============================================
            // 步骤 1：构建运行配置
            // ============================================
            // RunnableConfig：配置 Graph 的运行参数
            // - threadId：用于 Graph 执行状态的持久化和恢复（对应你学过的 Saver）
            // - metadata：附加元数据，这里把 user_id 放入 metadata
            RunnableConfig runnableConfig = RunnableConfig.builder()
                    .threadId(chatID)                            // 以 chatID 作为 threadId
                    .addMetadata("user_id", userID)              // 附加 user_id 元数据
                    .build();

            // ============================================
            // 步骤 2：构建输入数据
            // ============================================
            // 将 userID 嵌入到 userQuery 中，子 Agent 可以从中解析
            // 格式：原始用户输入 + <userId>用户ID</userId>
            String userInput = userQuery + "<userId>" + userID + "</userId>";

            // 构建 state 的初始 Map
            // 这些 key 必须与 SupervisorAgent 中 stateFactory 定义的 key 一致
            Map<String, Object> input = Map.of(
                    "input", userInput,    // 用户输入（含嵌入的 userID）
                    "chat_id", chatID,     // 会话 ID
                    "user_id", userID);    // 用户 ID（也单独存一份）

            // ============================================
            // 步骤 3：创建 SSE 管道
            // ============================================
            // Sinks.Many：响应式编程中的"可编程生产者"
            // unicast()：单播模式，只允许一个订阅者
            // onBackpressureBuffer()：背压策略为缓冲
            Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

            // ============================================
            // 步骤 4：获取编译后的 Graph 并流式执行
            // ============================================
            // getAndCompileGraph()：获取 Agent 内部的 Graph 并编译
            // fluxStream(input, config)：流式执行 Graph，返回 Flux<NodeOutput>
            // 每个 NodeOutput 包含：
            //   - node()：产生输出的节点名称
            //   - 如果是 StreamingOutput 类型，则包含 chunk() 方法获取流式文本块
            CompiledGraph compiledGraph = supervisorAgent.getAndCompileGraph();
            Flux<NodeOutput> result = compiledGraph.fluxStream(input, runnableConfig);

            // ============================================
            // 步骤 5：处理流式输出，转换为 SSE 事件
            // ============================================
            processStream(result, sink);

            // 返回 SSE Flux，并注册取消和错误的回调
            return sink.asFlux()
                    .doOnCancel(() -> logger.info("Client disconnected from stream"))
                    .doOnError(e -> logger.error("Error occurred during streaming", e));

        } catch (Exception e) {
            logger.error("Failed to process user query: {}", userQuery, e);
            return Flux.just(ServerSentEvent.builder("系统处理出现错误，请稍后重试。").build());
        }
    }

    /**
     * ============================================
     * 流式输出处理：Graph 节点输出 → SSE 事件
     * ============================================
     *
     * 【处理流程】
     * Flux<NodeOutput>（包含所有 Graph 节点的输出）
     *   → filter: 只保留 "a2aNode" 节点的输出
     *   → filter: 只保留 StreamingOutput 类型的输出
     *   → map: 取出流式文本块（chunk）
     *   → filter: 过滤掉空内容和 "Agent State: submitted" 状态消息
     *   → map: 包装成 ServerSentEvent
     *   → 发送到 sink
     *
     * 【为什么只关心 a2aNode 的输出】
     * LlmRoutingAgent 内部 Graph 包含多个节点：
     * - preLlm：准备上下文（内部）
     * - llm：LLM 路由决策（内部）
     * - a2aNode：调用子 Agent 并返回结果（用户可见）
     * 只有 a2aNode 的输出是用户真正需要看到的内容。
     *
     * 【过滤 "Agent State: submitted"】
     * 这是 A2A 协议的状态消息，表示任务已提交给子 Agent，
     * 对用户没有意义，需要过滤掉。
     */
    public void processStream(Flux<NodeOutput> generator, Sinks.Many<ServerSentEvent<String>> sink) {
        generator
                // 打印每个节点输出，便于调试
                .doOnNext(output -> logger.info("output = {}", output))

                // 过滤：只保留 a2aNode 节点的输出
                .filter(output -> "a2aNode".equals(output.node()) && output instanceof StreamingOutput)

                // 转换：StreamingOutput → chunk 文本
                .cast(StreamingOutput.class)
                .map(StreamingOutput::chunk)

                // 过滤：去掉空内容、状态消息
                .filter(content -> content != null && !content.isEmpty()
                        && !content.equals("Agent State: submitted"))

                // 包装：chunk 文本 → ServerSentEvent
                .map(content -> ServerSentEvent.builder(content).build())

                // 发送到 sink
                .doOnNext(sink::tryEmitNext)

                // 错误处理：记录日志并发送错误提示
                .doOnError(e -> {
                    logger.error("Unexpected error in stream processing: {}", e.getMessage(), e);
                    sink.tryEmitNext(ServerSentEvent.builder("系统处理出现错误，请稍后重试。").build());
                })

                // 完成处理：关闭 sink
                .doOnComplete(() -> {
                    logger.info("Stream processing completed successfully");
                    sink.tryEmitComplete();
                })

                // 订阅（启动流处理）
                .subscribe(
                        null,  // onNext 已在 doOnNext 中处理
                        // onError 回调
                        e -> {
                            logger.error("Stream processing failed: {}", e.getMessage(), e);
                            sink.tryEmitError(e);
                        }
                );
    }
}
/*
 * Copyright 2026-2027 the original author or authors.
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
package com.cloud.alibaba.ai.example.agent.rag.controller;

// Note 1: RagAgentController 是 RAG Agent 的 Web 入口, 提供 POST /api/rag/chat 接口。
//
// 对比前面模块的 Controller:
//   第6站 react-agent: /invoke + /feedback 两步 (HITL)
//   第10站 subagent:   单接口 + nodeId (流式 HITL)
//   本站 rag-agent:    标准 POST + threadId (多轮对话, 无 HITL)
//
// 本站 Controller 比较常规: 接收消息 → 配 threadId → 调 Agent → 取响应 → 返回
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.UUID;

/**
 * Controller for the RAG Agent
 * <p>
 * Provides REST API endpoints for interacting with the RAG agent,
 * as well as a simple web UI for demonstration purposes.
 * </p>
 *
 * @author zth9
 * @since 2026-01-22
 */
@Controller
@RequestMapping("/api/rag")
public class RagAgentController {

    private static final Logger logger = LoggerFactory.getLogger(RagAgentController.class);

    // Note 2: 注入 ReactAgent (RagAgentConfiguration 里建的 ragAgent Bean)。
    private final ReactAgent ragAgent;

    public RagAgentController(ReactAgent ragAgent) {
        this.ragAgent = ragAgent;
    }

    // Note 3: 根路径返回 "index" 视图 (前端 demo 页面)。
    @GetMapping("/")
    public String index() {
        return "index";
    }

    // Note 4: ★ 核心接口: POST /api/rag/chat —— 接收消息, 返回 Agent 回答。
    @PostMapping("/chat")
    @ResponseBody
    public ChatResponse chat(@RequestBody ChatRequest request) {
        logger.info("Received chat request: {}", request.message());

        // Note 5: threadId 管理——没传就生成 UUID。
        // threadId 用于多轮对话: 同一个 threadId 的消息共享上下文 (靠 MemorySaver)。
        String threadId = request.threadId();
        if (threadId == null || threadId.isEmpty()) {
            threadId = UUID.randomUUID().toString();
        }

        try {
            // Note 6: ★ 构建 RunnableConfig 带 threadId。
            // Agent 凭 threadId 从 MemorySaver 存/取状态, 实现多轮对话。
            RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

            // Note 7: ★ 调用 Agent——invokeAndGetOutput 是 ReactAgent 的核心方法。
            // 传入用户消息 + 配置, 返回 NodeOutput (含最终状态)。
            // Agent 内部 ReAct 循环: 决定调不调 knowledge_retrieval → 检索 → 综合 → 返回
            NodeOutput result = ragAgent.invokeAndGetOutput(request.message(), config).orElse(null);

            // Note 8: 从 NodeOutput 提取最终响应文本。
            String response = extractResponse(result);

            logger.info("Agent response: {}", response);
            return new ChatResponse(response, threadId, true);
        }
        catch (Exception e) {
            logger.error("Error processing chat request", e);
            return new ChatResponse("Sorry, an error occurred: " + e.getMessage(), threadId, false);
        }
    }

    // Note 9: GET 版本——方便用浏览器/curl 测试。
    @GetMapping("/chat")
    @ResponseBody
    public ChatResponse chatGet(@RequestParam("message") String message,
            @RequestParam(value = "threadId", required = false) String threadId) {
        return chat(new ChatRequest(message, threadId));
    }

    // Note 10: ★ 从 NodeOutput 提取响应——尝试多个 key 兜底。
    // ReactAgent 的输出可能存在 state 的 "output" 或 "messages" 里, 这里都试一遍。
    private String extractResponse(NodeOutput result) {
        if (result == null) {
            return "No response generated.";
        }

        OverAllState state = result.state();

        // Try "output" key first (common for ReactAgent)
        // Note 11: 优先取 "output" key (ReactAgent 常用)。
        Optional<Object> output = state.value("output");
        if (output.isPresent()) {
            return String.valueOf(output.get());
        }

        // Fallback to "messages" key
        // Note 12: 兜底取 "messages" key 的最后一条 (LLM 最终回答)。
        Optional<List<AbstractMessage>> messages = state.value("messages");
        if (messages.isPresent() && !messages.get().isEmpty()) {
            List<AbstractMessage> msgList = messages.get();
            return msgList.get(msgList.size() - 1).getText();
        }

        // Last resort: return state string representation
        // Note 13: 最后兜底: 返回 state 的字符串表示。
        return state.toString();
    }

    // Note 14: 两个 record——请求/响应 DTO。
    // ChatRequest:  message (用户消息) + threadId (会话ID, 可选)
    // ChatResponse: response (Agent回答) + threadId + success (是否成功)
    public record ChatRequest(String message, String threadId) {
    }

    public record ChatResponse(String response, String threadId, boolean success) {
    }

}

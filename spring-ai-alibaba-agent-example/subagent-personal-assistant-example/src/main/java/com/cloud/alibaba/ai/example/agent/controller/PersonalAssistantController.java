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

package com.cloud.alibaba.ai.example.agent.controller;

// Note 1: ★ PersonalAssistantController 是 Supervisor Agent 的 Web 入口, 用 SSE 流式返回。
//
// 对比第6站 react-agent 的 Controller:
//   第6站: /invoke + /feedback 两步 (HITL 两次 HTTP 请求)
//   本站:  单接口 + 流式, 通过 nodeId 判断是「首次调用」还是「恢复执行」
//
// 核心逻辑:
//   1. 用户带 query + threadId 来 → 启动 supervisor
//   2. supervisor 跑到子 Agent (calendar/email) 触发 HITL 暂停 → 流式返回中断信息
//   3. 用户带 nodeId (标记是恢复) → 取审批结果, 恢复执行
//
// ★ 关键: 用 nodeId 是否在 TOOL_FEEDBACK_MAP 里, 区分「首次」vs「恢复」。
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.cloud.alibaba.ai.example.agent.HITLHelper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST controller for managing personal assistant functionalities.
 * This controller handles requests related to the supervisor agent,
 * including streaming responses and human-in-the-loop interventions.
 *
 * @author wangjx
 * @since 2026-02-13
 */
@RestController
public class PersonalAssistantController {

    // Note 2: ★ 缓存待审批的工具反馈。key=nodeId, value=待审批工具列表。
    // 首次调用暂停时存, 恢复时用 nodeId 取。
    // ConcurrentHashMap: Web 多线程并发, 需线程安全。
    private static final Map<String, List<InterruptionMetadata.ToolFeedback>> TOOL_FEEDBACK_MAP = new ConcurrentHashMap<>();

    @Autowired
    @Qualifier("supervisorAgent")
    private ReactAgent supervisorAgent;


    /**
     * Handles GET requests to the supervisor agent endpoint.
     * Supports both regular streaming and human-in-the-loop interventions.
     *
     * @param query    the user's query string
     * @param threadId the session thread identifier
     * @param nodeId   the node identifier for human intervention
     * @return a Flux stream of responses from the supervisor agent
     * @throws GraphRunnerException if there's an error during graph execution
     */
    // Note 3: ★★★ 核心接口: 流式 + HITL 二合一。
    // produces TEXT_EVENT_STREAM_VALUE = SSE 流式返回。
    @GetMapping(value = "/react/agent/supervisorAgent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux supervisorAgentTest(String query, String threadId, String nodeId) throws GraphRunnerException {
        RunnableConfig config;
        // Note 4: ★ 用 nodeId 判断是「恢复执行」还是「首次调用」。
        // nodeId 非空且在 TOOL_FEEDBACK_MAP 里 → 说明之前暂停过, 现在恢复。
        if (nodeId != null && TOOL_FEEDBACK_MAP.containsKey(nodeId)) {
            System.out.println("人工介入开始...");
            // Human intervention using checkpoint mechanism.
            // You must provide a thread ID to associate execution with a session thread,
            // so that conversations can be paused and resumed (required for human review).
            // Note 5: 取出之前暂停时存的待审批工具列表, 用 HITLHelper.approveAll 全部批准。
            InterruptionMetadata metadata = InterruptionMetadata.builder().toolFeedbacks(TOOL_FEEDBACK_MAP.get(nodeId)).build();
            InterruptionMetadata approvalMetadata = HITLHelper.approveAll(metadata);
            // Resume execution using approval decision
            // Note 6: ★ 构建恢复配置——同 threadId + 审批结果。
            // addHumanFeedback 把审批结果塞进配置, Agent 恢复时读它知道「全部批准」。
            config = RunnableConfig.builder()
                    .threadId(threadId) // Same thread ID
                    .addHumanFeedback(approvalMetadata)
                    .build();
            TOOL_FEEDBACK_MAP.remove(nodeId);  // 用完清理
            return supervisorAgent.stream(query, config)
                    .doOnNext(this::println);
        } else {
            // Note 7: 首次调用——只带 threadId, 无审批信息。
            config = RunnableConfig.builder()
                    .threadId(threadId)
                    .build();
        }
        return supervisorAgent.stream(query, config)
                .doOnNext(this::println);

    }

    // Note 8: ★ doOnNext 回调——每个流式输出块都过这个方法, 打印日志 + 检测中断。
    // 这是个「副作用」方法: 不修改流, 只观察并打印。
    private void println(NodeOutput nodeOutput) {
        if (nodeOutput instanceof StreamingOutput streamingOutput) {
            String node = streamingOutput.node();    // 节点名 (_AGENT_MODEL_ / _AGENT_TOOL_ 等)
            Message message = streamingOutput.message();
            if (message == null) {
                return;
            }
            // Note 9: 模型输出节点——打印 LLM 生成的文本 (流式分块)。
            if ("_AGENT_MODEL_".equals(node)) {
                System.out.print(message.getText());
            }
            // Note 10: 工具输出节点——打印工具调用结果。
            if ("_AGENT_TOOL_".equals(node)) {
                ToolResponseMessage responseMessage = (ToolResponseMessage) message;
                List<ToolResponseMessage.ToolResponse> responses = responseMessage.getResponses();
                System.out.println("================================= Tool Message =================================\n");
                for (ToolResponseMessage.ToolResponse respons : responses) {
                    String string = respons.responseData();
                    System.out.println("id: " + respons.id());
                    System.out.println("name: " + respons.name());
                    System.out.println("responseData: " + string);

                }
            }
        } else if (nodeOutput instanceof InterruptionMetadata interruptionMetadata) {
            // Note 11: ★★★ 检测到中断——HITL 暂停!
            // supervisor 调子 Agent (calendar/email) 触发审批钩子, 流里会出 InterruptionMetadata。
            System.out.println("检测到中断，需要人工审批");
            List<InterruptionMetadata.ToolFeedback> toolFeedbacks =
                    interruptionMetadata.toolFeedbacks();

            // 打印待审批的工具信息 (工具名/参数/描述)
            for (InterruptionMetadata.ToolFeedback feedback : toolFeedbacks) {
                System.out.println("工具: " + feedback.getName());
                System.out.println("参数: " + feedback.getArguments());
                System.out.println("描述: " + feedback.getDescription());
            }
            String node = interruptionMetadata.node();
            System.out.println("检测到中断,等待人工介入... node: " + node);
            // Note 12: ★ 把待审批工具存进 map, key=nodeId。
            // 用户恢复时带这个 nodeId, Controller 就能取出审批 (approveAll) 并恢复执行。
            TOOL_FEEDBACK_MAP.put(node, toolFeedbacks);
        }
    }
}

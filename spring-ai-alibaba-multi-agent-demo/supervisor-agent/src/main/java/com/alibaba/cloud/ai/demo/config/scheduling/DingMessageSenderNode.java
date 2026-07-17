/*
 * Copyright 2025 the original author or authors.
 * ...
 */

package com.alibaba.cloud.ai.demo.config.scheduling;

import java.util.HashMap;
import java.util.Map;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * ============================================
 * 钉钉消息发送器（Graph 节点）
 * ============================================
 *
 * 【核心作用】
 * 实现 NodeAction 接口，作为 Graph 中的一个节点，负责将 Agent 生成的分析报告
 * 通过钉钉 Webhook 机器人发送到指定的钉钉群。
 *
 * 【为什么是 Graph 节点】
 * 在定时 Agent 的执行流程中，最后一步通常是把结果发送给管理员。
 * 把"发送消息"封装成 Graph 节点，可以：
 * 1. 与 Graph 的 State 机制无缝集成（从 State 读取消息内容）
 * 2. 统一错误处理（发送失败不会影响前序节点的执行结果）
 * 3. 可复用（DailyReportAgent 和 EvaluationAgent 都使用这个节点）
 *
 * 【钉钉消息格式】
 * 支持 Markdown 格式的消息，包含 title（标题）和 text（正文）。
 * 消息通过钉钉群机器人的 Webhook URL 发送。
 *
 * 【Graph 集成示例】
 *   StateGraph.addNode("message_sender", node_async(dingMessageSender))
 *            .addEdge("data_analysis", "message_sender")
 *            .addEdge("message_sender", END)
 */
public class DingMessageSenderNode implements NodeAction {

    /** 钉钉机器人 Webhook URL 模板 */
    private static final String DEFAULT_WEBHOOK_URL_TEMPLATE =
            "https://oapi.dingtalk.com/robot/send?access_token=%s";

    /** 应用配置中的 access token（默认值） */
    private final String accessToken;

    /** State 中 access token 的 key（运行时覆盖） */
    private final String accessTokenKey;

    /** State 中消息内容的 key */
    private final String messageContentKey;

    /** State 中发送结果的 key */
    private final String resultKey;

    /** 消息标题（如"门店经营日报"、"用户投诉分析监控"） */
    private final String title;

    /** 自定义 Webhook URL（可选，优先级高于默认模板） */
    private final String customWebhookUrl;

    public DingMessageSenderNode(String accessToken, String accessTokenKey,
                                  String messageContentKey, String resultKey, String title) {
        this(accessToken, accessTokenKey, messageContentKey, resultKey, title, null);
    }

    public DingMessageSenderNode(String accessToken, String accessTokenKey,
                                  String messageContentKey, String resultKey, String title,
                                  String customWebhookUrl) {
        this.accessToken = accessToken;
        this.messageContentKey = messageContentKey;
        this.resultKey = resultKey;
        this.title = title;
        this.customWebhookUrl = customWebhookUrl;
        this.accessTokenKey = accessTokenKey;
    }

    /**
     * Graph 节点执行方法
     *
     * 从 State 中读取消息内容，发送钉钉消息，返回发送结果。
     *
     * @param state Graph 的共享状态
     * @return 包含发送结果的 Map
     */
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 从 State 中获取消息内容
        Object message = state.value(messageContentKey).orElse(null);
        String messageContent;
        if (message instanceof AssistantMessage) {
            // 如果消息是 AssistantMessage 类型（LLM 节点输出），提取文本
            messageContent = ((AssistantMessage) message).getText();
        } else {
            messageContent = (String) message;
        }

        if (!StringUtils.hasLength(messageContent)) {
            String errorMsg = "Message content is empty or not found in state with key: " + messageContentKey;
            return Map.of(resultKey, errorMsg);
        }

        // 获取 access token（优先使用 State 中的值，否则使用配置中的默认值）
        Object accessToken = this.accessToken;
        if (StringUtils.hasText(accessTokenKey)) {
            accessToken = state.value(accessTokenKey).orElse(this.accessToken);
        }

        try {
            String response = sendMessage(accessToken.toString(), messageContent);
            return Map.of(resultKey, response);
        } catch (Exception e) {
            String errorMsg = "Failed to send DingDing message: " + e.getMessage();
            return Map.of(resultKey, errorMsg);
        }
    }

    /**
     * 发送钉钉消息
     */
    private String sendMessage(String accessToken, String messageContent) throws JsonProcessingException {
        String webhookUrl = StringUtils.hasLength(customWebhookUrl)
                ? customWebhookUrl
                : String.format(DEFAULT_WEBHOOK_URL_TEMPLATE, accessToken);

        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = createRequestBody(messageContent);
        String requestBodyJson = new ObjectMapper().writeValueAsString(requestBody);

        HttpEntity<String> request = new HttpEntity<>(requestBodyJson, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(webhookUrl, request, String.class);

        return response.getBody();
    }

    /**
     * 构建钉钉 Markdown 消息的请求体
     * 格式：{"msgtype": "markdown", "markdown": {"title": "...", "text": "..."}}
     */
    private Map<String, Object> createRequestBody(String messageContent) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("msgtype", "markdown");

        Map<String, String> markdown = new HashMap<>();
        markdown.put("title", title);
        markdown.put("text", messageContent);
        requestBody.put("markdown", markdown);

        return requestBody;
    }

    // ============================================
    // Builder 模式
    // ============================================
    public static class Builder {
        private String accessToken;
        private String accessTokenKey;
        private String messageContentKey;
        private String resultKey = "dingding_message_result";
        private String title = "Notification";
        private String customWebhookUrl;

        public Builder accessToken(String accessToken)           { this.accessToken = accessToken; return this; }
        public Builder accessTokenKey(String accessTokenKey)     { this.accessTokenKey = accessTokenKey; return this; }
        public Builder messageContentKey(String messageContentKey) { this.messageContentKey = messageContentKey; return this; }
        public Builder resultKey(String resultKey)               { this.resultKey = resultKey; return this; }
        public Builder title(String title)                       { this.title = title; return this; }
        public Builder customWebhookUrl(String customWebhookUrl) { this.customWebhookUrl = customWebhookUrl; return this; }

        public DingMessageSenderNode build() {
            return new DingMessageSenderNode(accessToken, accessTokenKey,
                    messageContentKey, resultKey, title, customWebhookUrl);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
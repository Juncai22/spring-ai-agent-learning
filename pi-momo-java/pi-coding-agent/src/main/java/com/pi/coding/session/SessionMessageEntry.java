package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.agent.types.AgentMessage;

/**
 * 包含 Agent 消息的会话条目。
 *
 * <p>这是最常见的条目类型，存储用户消息、助手回复和工具结果，
 * 作为对话历史的一部分。每条消息有一个 role（user/assistant/tool）和内容。
 *
 * <p>验证需求：1.3
 *
 * @param type      固定为 "message"
 * @param id        唯一条目标识符
 * @param parentId  父条目 ID（第一个条目为 null）
 * @param timestamp ISO 8601 时间戳
 * @param message   Agent 消息内容
 */
public record SessionMessageEntry(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("message") AgentMessage message
) implements SessionEntry {

    /**
     * 创建新的消息条目。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param message   Agent 消息
     * @return 新的 SessionMessageEntry
     */
    public static SessionMessageEntry create(String id, String parentId, String timestamp, AgentMessage message) {
        return new SessionMessageEntry("message", id, parentId, timestamp, message);
    }
}
package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录思考级别变更的会话条目。
 *
 * <p>思考级别控制模型执行的推理量（如 "off"、"low"、"medium"、"high"）。
 * 较高的思考级别会让模型花更多时间推理，通常能产生更高质量的回答，
 * 但也会增加延迟和 Token 消耗。
 *
 * <p>验证需求：1.4
 *
 * @param type          固定为 "thinking_level_change"
 * @param id            唯一条目标识符
 * @param parentId      父条目 ID（第一个条目为 null）
 * @param timestamp     ISO 8601 时间戳
 * @param thinkingLevel 新的思考级别值
 */
public record ThinkingLevelChangeEntry(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("thinkingLevel") String thinkingLevel
) implements SessionEntry {

    /**
     * 创建新的思考级别变更条目。
     *
     * @param id            唯一条目标识符
     * @param parentId      父条目 ID
     * @param timestamp     ISO 8601 时间戳
     * @param thinkingLevel 新的思考级别
     * @return 新的 ThinkingLevelChangeEntry
     */
    public static ThinkingLevelChangeEntry create(String id, String parentId, String timestamp, String thinkingLevel) {
        return new ThinkingLevelChangeEntry("thinking_level_change", id, parentId, timestamp, thinkingLevel);
    }
}
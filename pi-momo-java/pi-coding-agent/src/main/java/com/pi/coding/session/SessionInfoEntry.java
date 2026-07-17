package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 会话元数据条目：用于存储用户定义的显示名称。
 *
 * <p>允许用户为会话指定有意义的名称，便于在会话列表中识别。
 * 名称是可选的，可为 null。
 *
 * <p>验证需求：1.11
 *
 * @param type      固定为 "session_info"
 * @param id        唯一条目标识符
 * @param parentId  父条目 ID（第一个条目为 null）
 * @param timestamp ISO 8601 时间戳
 * @param name      用户定义的会话显示名称（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionInfoEntry(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("name") String name
) implements SessionEntry {

    /**
     * 创建新的会话信息条目。
     *
     * @param id        唯一条目标识符
     * @param parentId  父条目 ID
     * @param timestamp ISO 8601 时间戳
     * @param name      会话显示名称
     * @return 新的 SessionInfoEntry
     */
    public static SessionInfoEntry create(
            String id,
            String parentId,
            String timestamp,
            String name
    ) {
        return new SessionInfoEntry("session_info", id, parentId, timestamp, name);
    }
}
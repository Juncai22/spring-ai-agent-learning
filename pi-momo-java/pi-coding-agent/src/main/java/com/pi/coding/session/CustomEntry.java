package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 自定义条目：供扩展在会话中存储扩展特定数据。
 *
 * <p>使用 customType 标识扩展的条目。在会话重载时，
 * 扩展可以扫描其 customType 的条目并重建内部状态。
 *
 * <p><b>重要：</b>此条目不参与 LLM 上下文（buildSessionContext 会忽略）。
 * 如果需要向上下文中注入内容，请使用 {@link CustomMessageEntry}。
 *
 * <p>验证需求：1.8
 *
 * @param <T>        扩展特定数据的类型
 * @param type       固定为 "custom"
 * @param id         唯一条目标识符
 * @param parentId   父条目 ID（第一个条目为 null）
 * @param timestamp   ISO 8601 时间戳
 * @param customType 扩展标识符，用于过滤条目
 * @param data       扩展特定数据（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomEntry<T>(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("customType") String customType,
        @JsonProperty("data") T data
) implements SessionEntry {

    /**
     * 创建新的自定义条目。
     *
     * @param id         唯一条目标识符
     * @param parentId   父条目 ID
     * @param timestamp  ISO 8601 时间戳
     * @param customType 扩展标识符
     * @param data       扩展特定数据（可为 null）
     * @param <T>        数据类型
     * @return 新的 CustomEntry
     */
    public static <T> CustomEntry<T> create(
            String id,
            String parentId,
            String timestamp,
            String customType,
            T data
    ) {
        return new CustomEntry<>("custom", id, parentId, timestamp, customType, data);
    }

    /**
     * 创建新的自定义条目，不包含数据。
     *
     * @param id         唯一条目标识符
     * @param parentId   父条目 ID
     * @param timestamp  ISO 8601 时间戳
     * @param customType 扩展标识符
     * @return 新的 CustomEntry
     */
    public static CustomEntry<Void> create(
            String id,
            String parentId,
            String timestamp,
            String customType
    ) {
        return create(id, parentId, timestamp, customType, null);
    }
}
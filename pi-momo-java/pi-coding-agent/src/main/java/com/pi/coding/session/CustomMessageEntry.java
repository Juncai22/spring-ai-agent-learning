package com.pi.coding.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 自定义消息条目：供扩展向 LLM 上下文注入消息。
 *
 * <p>与 {@link CustomEntry} 不同，此条目<b>确实参与</b> LLM 上下文。
 * 在 buildSessionContext() 中，内容会被转换为用户消息。
 * details 字段用于扩展特定的元数据（不会发送给 LLM）。
 *
 * <p>display 标志控制 TUI 渲染行为：
 * <ul>
 *   <li>{@code false}：完全隐藏</li>
 *   <li>{@code true}：使用不同的样式渲染（与用户消息区分）</li>
 * </ul>
 *
 * <p>验证需求：1.9
 *
 * @param <T>        扩展特定详细信息的类型
 * @param type       固定为 "custom_message"
 * @param id         唯一条目标识符
 * @param parentId   父条目 ID（第一个条目为 null）
 * @param timestamp  ISO 8601 时间戳
 * @param customType 扩展标识符，用于过滤条目
 * @param content    消息内容（String 或 List of ContentBlock）
 * @param display    是否在 TUI 中显示
 * @param details    扩展特定的元数据（可为 null，不会发送给 LLM）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomMessageEntry<T>(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("parentId") String parentId,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("customType") String customType,
        @JsonProperty("content") Object content,
        @JsonProperty("display") boolean display,
        @JsonProperty("details") T details
) implements SessionEntry {

    /**
     * 创建新的自定义消息条目，包含扩展详细信息。
     *
     * @param id         唯一条目标识符
     * @param parentId   父条目 ID
     * @param timestamp  ISO 8601 时间戳
     * @param customType 扩展标识符
     * @param content    消息内容
     * @param display    是否在 TUI 中显示
     * @param details    扩展特定详细信息（可为 null）
     * @param <T>        详细信息类型
     * @return 新的 CustomMessageEntry
     */
    public static <T> CustomMessageEntry<T> create(
            String id,
            String parentId,
            String timestamp,
            String customType,
            Object content,
            boolean display,
            T details
    ) {
        return new CustomMessageEntry<>("custom_message", id, parentId, timestamp, customType, content, display, details);
    }

    /**
     * 创建新的自定义消息条目，不包含扩展详细信息。
     *
     * @param id         唯一条目标识符
     * @param parentId   父条目 ID
     * @param timestamp  ISO 8601 时间戳
     * @param customType 扩展标识符
     * @param content    消息内容
     * @param display    是否在 TUI 中显示
     * @return 新的 CustomMessageEntry
     */
    public static CustomMessageEntry<Void> create(
            String id,
            String parentId,
            String timestamp,
            String customType,
            Object content,
            boolean display
    ) {
        return create(id, parentId, timestamp, customType, content, display, null);
    }
}
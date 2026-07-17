package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Tool execution result message. Sent after a tool call completes.
 * 工具执行结果消息。在工具调用完成后发送，包含工具的执行结果。
 *
 * @param role       always {@code "toolResult"} / 固定为 {@code "toolResult"}
 * @param toolCallId the ID of the {@link ToolCall} this result corresponds to / 对应的工具调用 ID
 * @param toolName   the name of the tool that was invoked / 被调用的工具名称
 * @param content    result content blocks (text and/or images) / 结果内容块（文本和/或图片）
 * @param details    optional additional details (nullable, any JSON-serializable object) / 可选附加详情（可 null，任意 JSON 可序列化对象）
 * @param isError    whether the tool execution resulted in an error / 工具执行是否出错
 * @param timestamp  Unix timestamp in milliseconds / 创建时间戳（毫秒）
 */
// 使用 Java record 定义不可变数据载体，自动生成构造器、equals、hashCode、toString
// 工具结果消息：LLM 调用工具后，将工具执行结果以该消息类型返回给 LLM
public record ToolResultMessage(
    // 角色标识，JSON 序列化字段名为 "role"
    @JsonProperty("role") String role,
    // 关联的 ToolCall 的唯一 ID，用于将结果与对应的工具调用请求匹配
    @JsonProperty("toolCallId") String toolCallId,
    // 被调用的工具名称，用于 LLM 确认是哪个工具返回了结果
    @JsonProperty("toolName") String toolName,
    // 工具执行结果的内容块列表（通常是文本，也可以是图片）
    @JsonProperty("content") List<UserContentBlock> content,
    // 附加详情（可选）：可包含 JSON 序列化对象，存储工具特有的额外信息
    @JsonProperty("details") Object details,
    // 是否执行出错，LLM 根据此字段决定是否重试或报告错误
    @JsonProperty("isError") boolean isError,
    // 创建时间戳（毫秒）
    @JsonProperty("timestamp") long timestamp
) implements Message {

    /**
     * Jackson deserialization constructor. The {@code role} property is consumed
     * by {@code @JsonTypeInfo} for type resolution, so it arrives as {@code null};
     * we default it to {@code "toolResult"}.
     * Jackson 反序列化构造器。{@code role} 属性被 {@code @JsonTypeInfo} 用于类型解析，
     * 因此反序列化时传入的 role 可能为 null，此处默认设为 {@code "toolResult"}。
     */
    // Step 1: 标记此构造器为 Jackson 反序列化入口
    @JsonCreator
    public ToolResultMessage(
        @JsonProperty("role") String role,
        @JsonProperty("toolCallId") String toolCallId,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("content") List<UserContentBlock> content,
        @JsonProperty("details") Object details,
        @JsonProperty("isError") boolean isError,
        @JsonProperty("timestamp") long timestamp
    ) {
        // Step 2: 如果 role 为 null（被 @JsonTypeInfo 消费了），则默认设为 "toolResult"
        // 原因：@JsonTypeInfo 的 visible=true 确保 role 字段可见，但某些场景下可能为 null
        this.role = role != null ? role : "toolResult";
        // Step 3: 直接赋值各字段，保持 Jackson 反序列化后的原始值
        this.toolCallId = toolCallId;
        this.toolName = toolName;
        this.content = content;
        this.details = details;
        this.isError = isError;
        this.timestamp = timestamp;
    }

    /**
     * Convenience constructor that sets role automatically.
     * 便捷构造方法，自动设置 role 为 "toolResult"。
     *
     * @param toolCallId 对应的工具调用 ID
     * @param toolName   被调用的工具名称
     * @param content    结果内容块列表
     * @param details    可选附加详情
     * @param isError    工具执行是否出错
     * @param timestamp  创建时间戳（毫秒）
     */
    // Step 1: 便捷构造方法，自动设置 role 为 "toolResult"
    // 原因：调用方无需关心 role 字段，框架自动填充
    public ToolResultMessage(String toolCallId, String toolName,
                             List<UserContentBlock> content, Object details,
                             boolean isError, long timestamp) {
        // Step 2: 委托给主构造器，role 固定为 "toolResult"
        this("toolResult", toolCallId, toolName, content, details, isError, timestamp);
    }
}
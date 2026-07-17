package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User input message. Content can be either a plain text {@link String}
 * or a {@code List<UserContentBlock>} for mixed text/image content.
 * 用户输入消息。内容可以是纯文本字符串，也可以是包含文本和图片的混合内容块列表。
 *
 * <p>Jackson handles the polymorphic {@code content} field naturally:
 * a JSON string deserializes as {@code String}, a JSON array deserializes
 * as {@code List<UserContentBlock>}.
 * Jackson 原生支持多态的 content 字段：JSON 字符串反序列化为 String，
 * JSON 数组反序列化为 List&lt;UserContentBlock&gt;。
 *
 * @param role      always {@code "user"} / 固定为 {@code "user"}
 * @param content   plain text string or list of {@link UserContentBlock} / 纯文本字符串或内容块列表
 * @param timestamp Unix timestamp in milliseconds / 创建时间戳（毫秒）
 */
// 使用 Java record 定义不可变数据载体，自动生成构造器、equals、hashCode、toString
public record UserMessage(
    // 角色标识，JSON 序列化字段名为 "role"
    @JsonProperty("role") String role,
    // 消息内容：可以是 String（纯文本），也可以是 List<UserContentBlock>（文本+图片混合）
    @JsonProperty("content") Object content,
    // 创建时间戳（毫秒），JSON 序列化字段名为 "timestamp"
    @JsonProperty("timestamp") long timestamp
) implements Message {

    /**
     * Jackson deserialization constructor. The {@code role} property is consumed
     * by {@code @JsonTypeInfo} for type resolution, so it arrives as {@code null};
     * we default it to {@code "user"}.
     * Jackson 反序列化构造器。{@code role} 属性被 {@code @JsonTypeInfo} 用于类型解析，
     * 因此反序列化时传入的 role 可能为 null，此处默认设为 {@code "user"}。
     */
    // Step 1: 标记此构造器为 Jackson 反序列化入口
    @JsonCreator
    public UserMessage(
        // Step 1a: 从 JSON 中读取 role 字段
        @JsonProperty("role") String role,
        // Step 1b: 从 JSON 中读取 content 字段（多态类型，可能是 String 或 List）
        @JsonProperty("content") Object content,
        // Step 1c: 从 JSON 中读取 timestamp 字段
        @JsonProperty("timestamp") long timestamp
    ) {
        // Step 2: 如果 role 为 null（被 @JsonTypeInfo 消费了），则默认设为 "user"
        // 原因：@JsonTypeInfo 的 visible=true 确保 role 字段可见，但某些场景下可能为 null
        this.role = role != null ? role : "user";
        // Step 3: 直接赋值 content，保持 Jackson 反序列化后的原始类型
        this.content = content;
        // Step 4: 直接赋值 timestamp
        this.timestamp = timestamp;
    }

    /**
     * Convenience constructor for plain text content.
     * 便捷构造方法，用于纯文本内容的消息。
     *
     * @param textContent 文本内容
     * @param timestamp   创建时间戳（毫秒）
     */
    // Step 1: 便捷构造方法，仅需传入文本内容和时间戳
    // 原因：当用户消息仅为纯文本时，无需构造 List<UserContentBlock>
    public UserMessage(String textContent, long timestamp) {
        // Step 2: 委托给主构造器，role 固定为 "user"
        // 注意：content 参数类型为 String，与 record 的 Object 类型兼容
        this("user", textContent, timestamp);
    }

    /**
     * Convenience constructor for structured content blocks.
     * 便捷构造方法，用于结构化内容块（如文本+图片混合）的消息。
     *
     * @param blocks    内容块列表
     * @param timestamp 创建时间戳（毫秒）
     */
    // Step 1: 便捷构造方法，用于包含文本和图片的混合内容
    // 原因：当用户消息包含图片或其他结构化内容时，需要传入 List<UserContentBlock>
    public UserMessage(java.util.List<UserContentBlock> blocks, long timestamp) {
        // Step 2: 委托给主构造器，role 固定为 "user"
        // 注意：content 参数类型为 List<UserContentBlock>，与 record 的 Object 类型兼容
        this("user", blocks, timestamp);
    }
}
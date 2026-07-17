package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Unified message type for LLM conversations.
 * 统一的 LLM 对话消息类型，定义了所有消息类型的通用接口。
 *
 * <p>Uses Jackson polymorphic deserialization based on the {@code role} field
 * to dispatch to the correct concrete type.
 * 使用 Jackson 多态反序列化，根据 {@code role} 字段的值分发到具体的消息类型。
 *
 * <p>Permitted subtypes:
 * 允许的子类型：
 * <ul>
 *   <li>{@link UserMessage} — user input messages / 用户输入消息</li>
 *   <li>{@link AssistantMessage} — LLM response messages / 助手响应消息</li>
 *   <li>{@link ToolResultMessage} — tool execution result messages / 工具执行结果消息</li>
 * </ul>
 */
// Step 1: 配置 Jackson 多态类型信息，使用 role 字段作为类型标识
// 作用：序列化时写入 "role" 字段，反序列化时根据 role 值选择具体实现类
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role", visible = true)
// Step 2: 注册具体子类型及其对应的 role 值
// visible = true 表示 role 字段在反序列化后仍然可见（不会被 Jackson 消费掉）
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ToolResultMessage.class, name = "toolResult")
})
// 密封接口：限制 Message 的实现只能是指定的三种类型
public sealed interface Message permits UserMessage, AssistantMessage, ToolResultMessage {

    /**
     * The role discriminator identifying the message type.
     * 角色标识符，用于区分消息类型（"user"、"assistant"、"toolResult"）。
     *
     * @return 角色字符串，如 "user"、"assistant"、"toolResult"
     */
    // 接口方法：子类必须实现，返回消息的角色标识
    String role();

    /**
     * Unix timestamp in milliseconds when this message was created.
     * 消息创建时的 Unix 时间戳（毫秒）。
     *
     * @return 创建时间戳（毫秒）
     */
    // 接口方法：子类必须实现，返回消息的创建时间戳
    long timestamp();
}
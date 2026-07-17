package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Agent 层消息接口。
 *
 * <p>这是一个<b>非封闭（non-sealed）</b>接口，允许应用程序在标准的 LLM 消息类型
 * （{@code UserMessage}、{@code AssistantMessage}、{@code ToolResultMessage}）之外，
 * 自定义消息类型（如通知、产物消息等）。
 *
 * <p>标准的 LLM 消息通过 {@link MessageAdapter} 适配；自定义实现只需提供
 * {@link #role()} 和 {@link #timestamp()} 方法。
 *
 * <p>配置了 Jackson 多态序列化，将 {@link MessageAdapter} 作为默认子类型。
 * 自定义实现若需要序列化支持，应通过 Jackson 的 {@code ObjectMapper} 配置注册额外的子类型。
 *
 * <p><b>验证需求：8.1, 38.1, 38.4, 40.1</b>
 *
 * @see MessageAdapter
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@agentMessageType", defaultImpl = MessageAdapter.class)
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageAdapter.class, name = "messageAdapter")
})
public interface AgentMessage {

    /**
     * 角色标识符，用于区分消息类型
     * （例如 {@code "user"}、{@code "assistant"}、{@code "toolResult"}，或自定义角色）。
     *
     * @return 消息角色字符串
     */
    String role();

    /**
     * 消息创建时的 Unix 时间戳（毫秒级）。
     *
     * @return Unix 毫秒时间戳
     */
    long timestamp();
}
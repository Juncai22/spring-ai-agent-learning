package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.pi.ai.core.types.Message;

/**
 * 适配器，将 pi-ai-core 的 {@link Message} 包装为 {@link AgentMessage}。
 *
 * <p>由于 {@code Message} 是一个封闭接口（sealed interface），只允许
 * {@code UserMessage}、{@code AssistantMessage} 和 {@code ToolResultMessage} 三种实现，
 * 因此无法将 {@code AgentMessage} 添加到其许可列表中。此 record 通过委托方式
 * 桥接两个类型体系。
 *
 * <h3>静态辅助方法</h3>
 * <ul>
 *   <li>{@link #wrap(Message)} — 将 {@code Message} 包装为 {@code MessageAdapter}</li>
 *   <li>{@link #unwrap(AgentMessage)} — 提取底层的 {@code Message}
 *       （如果参数不是 {@code MessageAdapter} 则抛出异常）</li>
 *   <li>{@link #isLlmMessage(AgentMessage)} — 检查消息是否包装了标准的 LLM {@code Message}</li>
 * </ul>
 *
 * <p><b>验证需求：8.2, 8.3, 8.4, 8.5, 38.2, 40.1</b>
 *
 * @param message 被包装的 pi-ai-core {@link Message}
 */
@JsonTypeName("messageAdapter")
public record MessageAdapter(@JsonProperty("message") Message message) implements AgentMessage {

    /**
     * 创建一个非空消息的 {@code MessageAdapter}。
     *
     * @throws NullPointerException 如果 {@code message} 为 null
     */
    public MessageAdapter {
        if (message == null) {
            throw new NullPointerException("message must not be null");
        }
    }

    @Override
    public String role() {
        return message.role();
    }

    @Override
    public long timestamp() {
        return message.timestamp();
    }

    // ---- 静态工厂方法 / 工具方法 ----

    /**
     * 将 pi-ai-core 的 {@link Message} 包装为 {@link AgentMessage}。
     *
     * @param message 要包装的消息
     * @return 一个新的 {@code MessageAdapter} 实例
     * @throws NullPointerException 如果 {@code message} 为 null
     */
    public static AgentMessage wrap(Message message) {
        return new MessageAdapter(message);
    }

    /**
     * 从 {@link AgentMessage} 中提取底层的 pi-ai-core {@link Message}。
     *
     * @param agentMessage 要解包的 Agent 消息
     * @return 底层的 {@code Message} 实例
     * @throws IllegalArgumentException 如果 {@code agentMessage} 不是 {@code MessageAdapter} 类型
     */
    public static Message unwrap(AgentMessage agentMessage) {
        if (agentMessage instanceof MessageAdapter adapter) {
            return adapter.message();
        }
        throw new IllegalArgumentException(
                "Not a wrapped Message: " + agentMessage.getClass().getName());
    }

    /**
     * 判断给定的 {@link AgentMessage} 是否包装了标准的 LLM {@link Message}
     * （即是否为 {@code MessageAdapter} 实例）。
     *
     * @param agentMessage 要检查的消息
     * @return 如果该消息是包装后的 LLM 消息，则返回 {@code true}
     */
    public static boolean isLlmMessage(AgentMessage agentMessage) {
        return agentMessage instanceof MessageAdapter;
    }
}
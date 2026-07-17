package com.pi.agent.config;

import com.pi.agent.types.AgentMessage;
import com.pi.ai.core.types.Message;

import java.util.List;

/**
 * 消息转换回调接口，用于将 Agent 层的 {@link AgentMessage} 列表转换为
 * pi-ai-java 框架的 {@link Message} 列表，供 LLM 消费。
 *
 * <p>Agent 内部使用自己定义的消息类型（AgentMessage）来管理对话历史，
 * 但在将消息发送给 LLM 之前，需要将这些消息转换为 LLM 可以理解的格式。
 * 该接口就是负责这一转换过程的桥梁。
 *
 * <p>默认实现会过滤掉非 LLM 消息（如自定义类型），并解包
 * {@link com.pi.agent.types.MessageAdapter} 实例。
 *
 * <p>实现必须保证不抛出异常；在转换失败时应返回安全的降级值（如空列表），
 * 以确保 Agent 主循环的稳定性。
 *
 * <p><b>验证的需求：13.3</b>
 */
@FunctionalInterface
public interface ConvertToLlmFunction {

    /**
     * 将 Agent 层的消息列表转换为 LLM 兼容的消息列表。
     * <p>此方法在每次 LLM 调用前被调用，负责将 Agent 内部维护的对话历史
     * 转换为 LLM API 可识别的消息格式。
     *
     * @param messages 待转换的 Agent 消息列表
     * @return 转换后的 pi-ai-java {@link Message} 实例列表，用于 LLM 调用
     */
    List<Message> convert(List<AgentMessage> messages);
}

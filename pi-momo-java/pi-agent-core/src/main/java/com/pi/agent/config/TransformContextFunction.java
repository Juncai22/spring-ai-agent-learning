package com.pi.agent.config;

import com.pi.agent.types.AgentMessage;
import com.pi.ai.core.types.CancellationSignal;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 可选的回调接口，用于在消息列表传递给 {@link ConvertToLlmFunction} 之前
 * 对 {@link AgentMessage} 列表进行转换/预处理。
 *
 * <p>此钩子函数可用于实现以下功能：
 * <ul>
 *   <li>上下文窗口管理：当对话历史过长时，截断或压缩消息以适配 LLM 的上下文窗口限制</li>
 *   <li>消息过滤：移除不需要的消息，如调试信息、内部消息等</li>
 *   <li>对话摘要：对长对话进行摘要，用摘要替代部分历史消息以节省上下文空间</li>
 *   <li>消息增强：为消息添加额外信息，如时间戳、来源标记等</li>
 *   <li>多轮对话优化：合并连续的用户消息或助理消息以减少 token 消耗</li>
 *   <li>敏感信息过滤：在消息发送到 LLM 之前移除或替换敏感内容</li>
 * </ul>
 *
 * <p>实现必须保证不抛出异常；失败时应该返回原始消息列表或安全的降级值，
 * 以确保 Agent 主循环的稳定性。
 *
 * <p><b>验证的需求：13.5</b>
 */
@FunctionalInterface
public interface TransformContextFunction {

    /**
     * 转换 Agent 消息列表。
     * <p>此方法在消息被送入 {@link ConvertToLlmFunction} 转换之前被调用，
     * 用于对对话历史进行预处理。返回的列表将替换原始消息列表继续后续流程。
     *
     * @param messages 当前的 Agent 消息列表，包含完整的对话历史
     * @param signal   取消信号，用于协程式取消正在进行的操作
     * @return 一个 CompletableFuture，异步解析为转换后的消息列表
     */
    CompletableFuture<List<AgentMessage>> transform(List<AgentMessage> messages, CancellationSignal signal);
}

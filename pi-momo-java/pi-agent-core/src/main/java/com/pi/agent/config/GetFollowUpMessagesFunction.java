package com.pi.agent.config;

import com.pi.agent.types.AgentMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 可选的回调接口，用于获取待处理的后续消息（follow-up messages）。
 *
 * <p>Agent 主循环在以下情况下轮询此函数：
 * 没有更多工具调用（tool calls）且没有引导消息（steering messages）时。
 * 当存在后续消息时，它们会触发新一轮的外层循环迭代。
 *
 * <p>后续消息机制可用于实现以下场景：
 * <ul>
 *   <li>异步任务结果回调：异步任务完成后将结果注入对话</li>
 *   <li>外部事件驱动：外部系统事件触发新的消息注入</li>
 *   <li>多 Agent 协作：其他 Agent 的输出作为本 Agent 的输入</li>
 *   <li>延迟处理：需要等待外部条件满足后继续对话</li>
 * </ul>
 *
 * <p><b>验证的需求：13.9</b>
 */
@FunctionalInterface
public interface GetFollowUpMessagesFunction {

    /**
     * 返回待处理的后续消息。
     * <p>此方法在 Agent 主循环空闲时被调用，用于检查是否有新的消息需要处理。
     * 返回空列表表示没有后续消息，Agent 将结束当前轮次的循环。
     *
     * @return 一个 CompletableFuture，异步解析为后续消息列表，无消息时返回空列表
     */
    CompletableFuture<List<AgentMessage>> get();
}

package com.pi.agent.config;

import com.pi.agent.types.AgentMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 可选的回调接口，用于获取待处理的引导消息（steering messages），
 * 并将其注入到 Agent 主循环中。
 *
 * <p>Agent 主循环在每轮工具执行完成后轮询此函数。
 * 当存在引导消息时，它们会被追加到上下文（context）中，
 * 并触发新一轮 LLM 调用，然后再检查后续消息（follow-up messages）。
 *
 * <p>引导消息可以用于：
 * <ul>
 *   <li>外部干预：外部系统或用户向 Agent 注入指令或提示</li>
 *   <li>行为修正：在 Agent 偏离预期行为时进行引导纠正</li>
 *   <li>上下文增强：在工具执行后注入额外的上下文信息</li>
 *   <li>多 Agent 协调：协调者的输出作为工作 Agent 的引导输入</li>
 * </ul>
 *
 * <p>与 {@link GetFollowUpMessagesFunction} 的区别在于：引导消息在每轮工具执行后
 * 立即检查并处理，优先级高于后续消息。
 *
 * <p><b>验证的需求：13.8</b>
 */
@FunctionalInterface
public interface GetSteeringMessagesFunction {

    /**
     * 返回待处理的引导消息。
     * <p>此方法在每轮工具执行完成后被调用，用于检查是否有新的引导消息需要注入。
     * 返回空列表表示没有引导消息，Agent 将继续检查后续消息或结束本轮循环。
     *
     * @return 一个 CompletableFuture，异步解析为引导消息列表，无消息时返回空列表
     */
    CompletableFuture<List<AgentMessage>> get();
}

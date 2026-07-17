package com.pi.agent.types;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.ToolCall;

/**
 * 在工具执行之前传递给 {@link com.pi.agent.config.BeforeToolCallHook} 的上下文信息。
 *
 * <p>为钩子函数提供发起工具调用的助手消息、原始工具调用块、
 * 经过校验的参数以及当前的 Agent 上下文，以便钩子函数进行预处理或拦截决策。
 *
 * <p><b>验证需求：4.1</b>
 *
 * @param assistantMessage 发起工具调用的助手消息
 * @param toolCall         助手消息内容中的原始工具调用块
 * @param args             经过校验的工具参数（可能为 Map 或其他结构）
 * @param context          准备执行工具调用时的当前 Agent 上下文
 */
public record BeforeToolCallContext(
        AssistantMessage assistantMessage,
        ToolCall toolCall,
        Object args,
        AgentContext context
) {
}
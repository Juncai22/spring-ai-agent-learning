package com.pi.agent.types;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.ToolCall;

/**
 * 在工具执行之后传递给 {@link com.pi.agent.config.AfterToolCallHook} 的上下文信息。
 *
 * <p>为钩子函数提供发起工具调用的助手消息、工具调用块、经过校验的参数、
 * 执行结果、错误标志以及当前的 Agent 上下文，以便钩子函数审查或覆盖工具结果。
 *
 * <p><b>验证需求：4.2</b>
 *
 * @param assistantMessage 发起工具调用的助手消息
 * @param toolCall         助手消息内容中的原始工具调用块
 * @param args             经过校验的工具参数（可能为 Map 或其他结构）
 * @param result           工具执行结果（在 afterToolCall 覆盖之前的原始结果）
 * @param isError          当前工具执行结果是否被视为错误
 * @param context          工具调用完成时的当前 Agent 上下文
 */
public record AfterToolCallContext(
        AssistantMessage assistantMessage,
        ToolCall toolCall,
        Object args,
        AgentToolResult<?> result,
        boolean isError,
        AgentContext context
) {
}
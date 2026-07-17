package com.pi.agent.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 由 {@link com.pi.agent.config.BeforeToolCallHook} 返回的结果。
 *
 * <p>当 {@code block} 为 {@code true} 时，Agent 主循环将阻止工具执行，
 * 并生成一个包含 {@code reason} 的错误工具结果
 * （若 {@code reason} 为 {@code null} 则使用默认消息）。
 *
 * @param block  如果为 {@code true}，则阻止该工具调用
 * @param reason 人类可读的解释说明，将显示在错误工具结果中
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BeforeToolCallResult(
        Boolean block,
        String reason
) {
}
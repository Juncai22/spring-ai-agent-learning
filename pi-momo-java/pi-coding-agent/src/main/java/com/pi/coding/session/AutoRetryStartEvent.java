package com.pi.coding.session;

/**
 * 自动重试开始事件。
 *
 * <p>当 Agent 遇到可重试错误（如过载、速率限制、服务器错误等）
 * 并即将执行自动重试时触发此事件。
 * 包含当前重试次数、延迟时间和错误原因。
 *
 * @param attempt 当前重试尝试次数（从 1 开始计数）
 * @param delayMs 重试前的延迟毫秒数（基于指数退避策略计算）
 * @param reason  触发重试的错误原因描述
 */
public record AutoRetryStartEvent(
        int attempt,
        long delayMs,
        String reason
) implements AgentSessionEvent {
}
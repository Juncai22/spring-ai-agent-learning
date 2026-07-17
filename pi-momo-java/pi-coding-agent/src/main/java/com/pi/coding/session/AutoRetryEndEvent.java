package com.pi.coding.session;

/**
 * 自动重试结束事件。
 *
 * <p>当自动重试执行完成、成功或被中止时触发此事件。
 * 监听器可以根据此事件更新 UI 或重置重试状态。
 *
 * @param attempt 已完成的重试尝试次数
 * @param success 重试是否成功（Agent 正常完成处理）
 * @param aborted 重试是否被用户中止
 */
public record AutoRetryEndEvent(
        int attempt,
        boolean success,
        boolean aborted
) implements AgentSessionEvent {
}
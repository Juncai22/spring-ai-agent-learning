package com.pi.coding.session;

import com.pi.coding.compaction.CompactionResult;

/**
 * 自动压缩结束事件。
 *
 * <p>当自动压缩完成、失败或被中止时触发此事件。
 * 包含压缩结果、状态信息和错误消息。
 * 监听器可以根据此事件更新 UI 或触发后续操作。
 *
 * @param result       压缩结果对象（如果失败或中止则为 null）
 * @param aborted      压缩是否被中止（用户取消）
 * @param willRetry    压缩后 Agent 是否将重试
 * @param errorMessage 如果压缩失败，包含错误信息（可为 null）
 */
public record AutoCompactionEndEvent(
        CompactionResult result,
        boolean aborted,
        boolean willRetry,
        String errorMessage
) implements AgentSessionEvent {
}
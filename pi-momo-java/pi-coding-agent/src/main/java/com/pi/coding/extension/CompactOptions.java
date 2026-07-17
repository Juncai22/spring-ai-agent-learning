package com.pi.coding.extension;

import com.pi.coding.compaction.CompactionResult;

import java.util.function.Consumer;

/**
 * 上下文压缩选项 —— 用于触发上下文压缩的配置。
 *
 * <p>上下文压缩会汇总会话历史，减少 Token 使用量。
 * 通过此选项可以自定义压缩行为，包括：
 * <ul>
 *   <li>自定义摘要指令：指导 LLM 如何生成摘要</li>
 *   <li>完成回调：压缩完成后调用</li>
 *   <li>错误回调：压缩失败时调用</li>
 * </ul>
 *
 * @param customInstructions 自定义摘要指令
 * @param onComplete         压缩完成时的回调
 * @param onError            压缩失败时的回调
 */
public record CompactOptions(
    String customInstructions,
    Consumer<CompactionResult> onComplete,
    Consumer<Exception> onError
) {

    /**
     * CompactOptions 的构建器。
     */
    public static class Builder {
        private String customInstructions;
        private Consumer<CompactionResult> onComplete;
        private Consumer<Exception> onError;

        public Builder customInstructions(String customInstructions) { this.customInstructions = customInstructions; return this; }

        public Builder onComplete(Consumer<CompactionResult> onComplete) { this.onComplete = onComplete; return this; }

        public Builder onError(Consumer<Exception> onError) { this.onError = onError; return this; }

        public CompactOptions build() {
            return new CompactOptions(customInstructions, onComplete, onError);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

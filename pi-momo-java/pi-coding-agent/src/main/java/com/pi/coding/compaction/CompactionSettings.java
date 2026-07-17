package com.pi.coding.compaction;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文压缩设置记录，控制上下文压缩的行为参数。
 *
 * <p>定义了自动压缩的开关、为响应生成预留的 token 数量以及保留最近消息的 token 预算。
 * 这些参数直接影响压缩触发的时机和压缩后保留的上下文量。
 *
 * <p><b>验证需求: 3.3</b>
 *
 * @param enabled         是否启用自动上下文压缩
 * @param reserveTokens   为响应生成预留的 token 数量（防止上下文溢出）
 * @param keepRecentTokens 从最近消息中保留的近似 token 数量
 */
public record CompactionSettings(
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("reserveTokens") int reserveTokens,
        @JsonProperty("keepRecentTokens") int keepRecentTokens
) {

    /**
     * 默认压缩设置：启用压缩，预留 16384 token 给响应，保留最近 20000 token。
     */
    public static final CompactionSettings DEFAULT = new CompactionSettings(true, 16384, 20000);

    /**
     * 创建禁用压缩的设置，保持其他参数为默认值。
     * 用于临时关闭自动压缩功能。
     *
     * @return 禁用压缩的 CompactionSettings 实例
     */
    public static CompactionSettings disabled() {
        return new CompactionSettings(false, DEFAULT.reserveTokens(), DEFAULT.keepRecentTokens());
    }
}
package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文压缩（Compaction）设置。
 *
 * <p>当 AI 对话的上下文窗口即将达到 Token 上限时，系统会对历史对话进行压缩，
 * 以减少 Token 占用，保留关键信息。压缩策略通过保留最近的对话内容
 * 并压缩较早期的内容来实现。
 *
 * <p>配置项说明：
 * <ul>
 *   <li><b>enabled</b> — 是否启用上下文压缩（默认：true）</li>
 *   <li><b>reserveTokens</b> — 压缩后保留的 Token 总数（默认：32000），用于控制压缩后的上下文大小</li>
 *   <li><b>keepRecentTokens</b> — 保留的最近对话 Token 数（默认：16000），
 *       这部分内容不会被压缩，完整保留</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactionSettings(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("reserveTokens") Integer reserveTokens,
    @JsonProperty("keepRecentTokens") Integer keepRecentTokens
) {
    /** 默认的压缩配置：启用压缩，保留 32000 Token，其中最近 16000 Token 完整保留 */
    public static final CompactionSettings DEFAULT = new CompactionSettings(true, 32000, 16000);

    /** 是否启用上下文压缩，未配置时默认返回 true */
    public boolean isEnabled() { return enabled != null ? enabled : true; }
    /** 获取压缩后保留的 Token 总数，未配置时默认返回 32000 */
    public int getReserveTokens() { return reserveTokens != null ? reserveTokens : 32000; }
    /** 获取保留的最近对话 Token 数（不会被压缩），未配置时默认返回 16000 */
    public int getKeepRecentTokens() { return keepRecentTokens != null ? keepRecentTokens : 16000; }
}

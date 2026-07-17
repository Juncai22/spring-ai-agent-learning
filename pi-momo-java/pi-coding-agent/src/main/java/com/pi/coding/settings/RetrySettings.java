package com.pi.coding.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 自动重试（Retry）设置。
 *
 * <p>当 AI API 调用失败（如网络超时、服务端错误等）时，系统会自动进行重试。
 * 重试策略采用指数退避（exponential backoff）算法：每次重试的延迟时间
 * 从 baseDelayMs 开始，逐次加倍，但不超过 maxDelayMs。
 *
 * <p>重试延迟计算示例：
 * <ul>
 *   <li>第 1 次重试：等待 baseDelayMs（默认 1000ms = 1 秒）</li>
 *   <li>第 2 次重试：等待 2 * baseDelayMs（默认 2000ms = 2 秒）</li>
 *   <li>第 3 次重试：等待 4 * baseDelayMs（默认 4000ms = 4 秒）</li>
 *   <li>... 以此类推，直到 maxDelayMs（默认 30000ms = 30 秒）</li>
 * </ul>
 *
 * <p>配置项说明：
 * <ul>
 *   <li><b>enabled</b> — 是否启用自动重试（默认：true）</li>
 *   <li><b>maxRetries</b> — 最大重试次数（默认：3 次）</li>
 *   <li><b>baseDelayMs</b> — 基础延迟时间，单位为毫秒（默认：1000ms），
 *       即首次重试前的等待时间</li>
 *   <li><b>maxDelayMs</b> — 最大延迟时间，单位为毫秒（默认：30000ms），
 *       防止指数退避导致的等待时间过长</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RetrySettings(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("maxRetries") Integer maxRetries,
    @JsonProperty("baseDelayMs") Integer baseDelayMs,
    @JsonProperty("maxDelayMs") Integer maxDelayMs
) {
    /** 默认重试配置：启用重试，最多重试 3 次，基础延迟 1 秒，最大延迟 30 秒 */
    public static final RetrySettings DEFAULT = new RetrySettings(true, 3, 1000, 30000);

    /** 是否启用自动重试，未配置时默认返回 true */
    public boolean isEnabled() { return enabled != null ? enabled : true; }
    /** 获取最大重试次数，未配置时默认返回 3 */
    public int getMaxRetries() { return maxRetries != null ? maxRetries : 3; }
    /** 获取基础延迟时间（毫秒），未配置时默认返回 1000 */
    public int getBaseDelayMs() { return baseDelayMs != null ? baseDelayMs : 1000; }
    /** 获取最大延迟时间（毫秒），未配置时默认返回 30000 */
    public int getMaxDelayMs() { return maxDelayMs != null ? maxDelayMs : 30000; }
}

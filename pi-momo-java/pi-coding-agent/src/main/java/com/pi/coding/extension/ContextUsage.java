package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 上下文使用情况 —— 当前活动模型的上下文使用信息。
 *
 * <p>提供当前上下文的 Token 使用量、上下文窗口大小和使用百分比，
 * 帮助用户和扩展了解当前会话的上下文压力。
 * 当 Token 使用量接近上下文窗口大小时，应考虑触发上下文压缩。
 *
 * @param tokens        估计的上下文 Token 数量，如果未知则为 null
 * @param contextWindow 上下文窗口大小（Token 数）
 * @param percent       上下文使用百分比（tokens / contextWindow），如果 tokens 未知则为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextUsage(
    @JsonProperty("tokens") Integer tokens,
    @JsonProperty("contextWindow") int contextWindow,
    @JsonProperty("percent") Double percent
) { }

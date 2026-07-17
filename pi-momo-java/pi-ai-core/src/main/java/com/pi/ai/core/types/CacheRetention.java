package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Cache retention policy for prompt caching.
 * 提示缓存的保留策略枚举，用于控制系统提示和历史消息的缓存方式。
 *
 * <ul>
 *   <li>{@link #NONE} — 不启用缓存</li>
 *   <li>{@link #SHORT} — 短时间缓存，适用于频繁变化的上下文</li>
 *   <li>{@link #LONG} — 长时间缓存，适用于稳定的系统提示</li>
 * </ul>
 */
// 枚举：提示缓存保留策略
// 控制哪些消息应该被缓存以及缓存多长时间
// 合理使用缓存可以显著降低 API 调用成本和延迟
public enum CacheRetention {

    // 不启用缓存：每次请求都发送完整上下文
    @JsonProperty("none")
    NONE,

    // 短时间缓存：适用于频繁变化的上下文
    // 例如：多轮对话中，最近几轮的消息使用短缓存
    @JsonProperty("short")
    SHORT,

    // 长时间缓存：适用于稳定的系统提示
    // 例如：系统提示、工具定义、对话开头的固定上下文
    @JsonProperty("long")
    LONG
}
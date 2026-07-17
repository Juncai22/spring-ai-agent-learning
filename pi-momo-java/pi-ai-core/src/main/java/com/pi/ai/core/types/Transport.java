package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Transport protocol for streaming connections.
 * 流式连接的传输协议枚举，用于指定与 API 通信的方式。
 *
 * <ul>
 *   <li>{@link #SSE} — Server-Sent Events，基于 HTTP 的单向流式传输</li>
 *   <li>{@link #WEBSOCKET} — WebSocket 全双工通信协议</li>
 *   <li>{@link #AUTO} — 自动选择最佳传输协议</li>
 * </ul>
 */
// 枚举：流式传输协议
// 决定客户端与 LLM API 之间如何建立流式连接
public enum Transport {

    // Server-Sent Events：基于 HTTP 长连接的单向流式传输
    // 标准 HTTP 协议，兼容性好，支持大多数 Provider
    @JsonProperty("sse")
    SSE,

    // WebSocket 全双工通信协议
    // 双向通信，延迟更低，适用于交互式场景
    @JsonProperty("websocket")
    WEBSOCKET,

    // 自动选择：由框架根据 Provider 和模型自动选择最佳传输协议
    @JsonProperty("auto")
    AUTO
}
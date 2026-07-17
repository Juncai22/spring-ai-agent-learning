package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reason why the LLM stopped generating.
 * LLM 停止生成的原因枚举，用于告知调用方模型为何结束响应。
 *
 * <ul>
 *   <li>{@link #STOP} — 模型自然停止，正常完成响应</li>
 *   <li>{@link #LENGTH} — 达到最大 Token 长度限制</li>
 *   <li>{@link #TOOL_USE} — 模型请求调用工具，等待工具结果</li>
 *   <li>{@link #ERROR} — 生成过程中发生错误</li>
 *   <li>{@link #ABORTED} — 请求被用户取消或中断</li>
 * </ul>
 */
// 枚举：LLM 停止生成的原因
// 每个枚举值通过 @JsonProperty 指定 JSON 序列化时的字段名
public enum StopReason {

    // 正常停止：模型完整生成了响应，没有遇到任何限制
    @JsonProperty("stop")
    STOP,

    // 长度限制：响应达到 maxTokens 设置的上限，被截断
    @JsonProperty("length")
    LENGTH,

    // 工具调用：模型请求调用工具，暂时停止生成等待工具结果
    @JsonProperty("toolUse")
    TOOL_USE,

    // 发生错误：生成过程中遇到异常（如内容审核、API 错误等）
    @JsonProperty("error")
    ERROR,

    // 请求被取消：用户通过 CancellationSignal 中断了请求
    @JsonProperty("aborted")
    ABORTED
}
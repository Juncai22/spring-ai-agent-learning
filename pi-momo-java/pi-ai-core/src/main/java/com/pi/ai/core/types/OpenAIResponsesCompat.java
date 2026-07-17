package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * OpenAI Responses API 的兼容性配置。
 * 用于适配 OpenAI 最新的 Responses API（替代 Chat Completions API）。
 *
 * <p>当前为空，预留给未来扩展。Responses API 是 OpenAI 的新一代 API 接口，
 * 与传统的 Completions API 在参数和响应格式上有所不同。
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的 OpenAI Responses API 兼容性配置
// 当前为空 record，不包含任何字段
// 预留给未来扩展：当 Responses API 需要特定配置时在此添加字段
public record OpenAIResponsesCompat() { }
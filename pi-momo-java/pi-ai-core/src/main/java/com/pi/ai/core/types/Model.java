package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * LLM 模型定义，包含模型元数据、定价、能力和兼容性配置。
 * 用于描述一个可用的 LLM 模型，包括其 API 协议、提供商、能力边界和定价信息。
 *
 * <p>Java 中没有条件类型，{@code compat} 字段使用 {@link OpenAICompletionsCompat} 类型。
 * 对于 OpenAI Responses API 模型，compat 为 null（{@link OpenAIResponsesCompat} 当前为空）。
 *
 * @param id            模型唯一标识（如 "claude-sonnet-4-20250514"）
 * @param name          模型显示名称（如 "Claude Sonnet 4"）
 * @param api           API 协议标识（如 "anthropic-messages"、"openai-completions"）
 * @param provider      服务提供商标识（如 "anthropic"、"openai"）
 * @param baseUrl       API 基础 URL
 * @param reasoning     是否支持推理/思考功能
 * @param input         支持的输入类型列表（如 ["text", "image"]）
 * @param cost          模型定价信息
 * @param contextWindow 上下文窗口大小（token 数），决定模型能处理的最大输入长度
 * @param maxTokens     最大输出 token 数，限制模型单次响应的最大长度
 * @param headers       自定义 HTTP 头（可选），用于需要额外请求头的 Provider
 * @param compat        兼容性配置（可选，仅 openai-completions API 使用）
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的模型配置
public record Model(
    // 模型唯一标识：用于在 API 请求中指定模型
    @JsonProperty("id") String id,
    // 模型显示名称：用于 UI 展示
    @JsonProperty("name") String name,
    // API 协议标识：决定使用哪种 API 协议发送请求
    @JsonProperty("api") String api,
    // 服务提供商标识：如 "anthropic"、"openai"、"google" 等
    @JsonProperty("provider") String provider,
    // API 基础 URL：Provider 的服务端点地址
    @JsonProperty("baseUrl") String baseUrl,
    // 是否支持推理/思考功能：控制是否可以使用 extended thinking
    @JsonProperty("reasoning") boolean reasoning,
    // 支持的输入类型列表：如 ["text"] 表示仅文本，["text", "image"] 表示多模态
    @JsonProperty("input") List<String> input,
    // 模型定价：按 Token 维度定义的单价
    @JsonProperty("cost") ModelCost cost,
    // 上下文窗口大小：模型能处理的最大 Token 输入量
    @JsonProperty("contextWindow") int contextWindow,
    // 最大输出 Token 数：限制模型单次响应的长度
    @JsonProperty("maxTokens") int maxTokens,
    // 自定义 HTTP 头：某些 Provider 需要额外的请求头
    @JsonProperty("headers") Map<String, String> headers,
    // 兼容性配置：仅 openai-completions API 使用，用于适配不同 Provider 的差异
    @JsonProperty("compat") OpenAICompletionsCompat compat
) { }
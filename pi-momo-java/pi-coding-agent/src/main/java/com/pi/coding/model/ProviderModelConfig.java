package com.pi.coding.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.ai.core.types.ModelCost;

/**
 * 提供商模型配置 —— 表示动态注册的提供商下的单个模型配置。
 *
 * <p>与 {@link ProviderConfig} 配合使用，定义了动态提供商中每个模型的具体参数，
 * 包括模型标识、显示名称、推理能力、支持输入类型、费用、上下文窗口大小
 * 和最大 Token 数等。</p>
 *
 * <p>序列化时，所有 null 字段将被忽略（{@link JsonInclude.Include#NON_NULL}）。</p>
 *
 * @param id            模型标识符，如 "gpt-4"、"claude-3-opus"
 * @param name          模型可读名称（可选，未设置时默认使用 id）
 * @param reasoning     是否支持推理/思考能力（如 Chain-of-Thought）
 * @param input         支持的输入类型，逗号分隔的字符串，如 "text,image"
 * @param cost          模型费用配置（输入/输出 Token 单价等）
 * @param contextWindow 上下文窗口大小（Token 数），默认 128000
 * @param maxTokens     最大输出 Token 数，默认 4096
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderModelConfig(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("reasoning") Boolean reasoning,
    @JsonProperty("input") String input,
    @JsonProperty("cost") ModelCost cost,
    @JsonProperty("contextWindow") Integer contextWindow,
    @JsonProperty("maxTokens") Integer maxTokens
) {}

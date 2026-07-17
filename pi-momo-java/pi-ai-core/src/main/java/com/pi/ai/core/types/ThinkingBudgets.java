package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 每个思考级别对应的 token 预算配置。
 * 用于自定义不同思考级别下的最大 Token 预算，精细控制模型的推理深度。
 *
 * <p>所有字段均为可选（nullable），未设置时使用默认预算值。
 *
 * @param minimal minimal 级别的 token 预算，适用于简单问题
 * @param low     low 级别的 token 预算
 * @param medium  medium 级别的 token 预算
 * @param high    high 级别的 token 预算，适用于复杂推理
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的思考 Token 预算配置
// 所有字段均为 Integer 而非 int，以支持 null（表示使用默认值）
public record ThinkingBudgets(
    // minimal 级别的最大 Token 预算
    @JsonProperty("minimal") Integer minimal,
    // low 级别的最大 Token 预算
    @JsonProperty("low") Integer low,
    // medium 级别的最大 Token 预算
    @JsonProperty("medium") Integer medium,
    // high 级别的最大 Token 预算
    @JsonProperty("high") Integer high
) { }
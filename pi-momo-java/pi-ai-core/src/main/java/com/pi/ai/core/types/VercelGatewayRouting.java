package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Vercel AI Gateway 路由偏好配置。
 * 控制 Vercel AI Gateway 将请求路由到哪些上游 Provider，用于多 Provider 的负载均衡和故障转移。
 *
 * <p>控制 Vercel AI Gateway 将请求路由到哪些上游 Provider。
 *
 * @param only  仅使用的 Provider slug 列表（可选），指定后仅路由到这些 Provider
 * @param order 按优先级排序的 Provider slug 列表（可选），按顺序依次尝试各 Provider
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的 Vercel AI Gateway 路由配置
public record VercelGatewayRouting(
    // only 模式：仅使用指定的 Provider 列表
    // 如果设置，Vercel Gateway 将只路由到列出的 Provider
    // 每个元素是 Provider 的 slug（如 "anthropic"、"openai"）
    @JsonProperty("only") List<String> only,
    // order 模式：按优先级排序的 Provider 列表
    // Vercel Gateway 按列表顺序尝试每个 Provider，实现故障转移
    @JsonProperty("order") List<String> order
) { }
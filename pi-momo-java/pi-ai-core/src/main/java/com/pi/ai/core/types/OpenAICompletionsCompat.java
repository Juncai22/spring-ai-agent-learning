package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * OpenAI 兼容 Completions API 的兼容性配置。
 * 用于适配各类兼容 OpenAI API 格式的 Provider，处理不同 Provider 之间的细微差异。
 *
 * <p>用于覆盖基于 URL 的自动检测，为自定义 Provider 手动设置兼容性开关。
 * 所有字段均为可选（nullable），未设置时使用自动检测值。
 *
 * @param supportsStore                    是否支持 store 字段（用于 OpenAI 的存储功能）
 * @param supportsDeveloperRole            是否支持 developer 角色（代替 system 角色）
 * @param supportsReasoningEffort          是否支持 reasoning_effort 参数（控制思考强度）
 * @param reasoningEffortMap               思考级别到 Provider 特定 reasoning_effort 值的映射
 * @param supportsUsageInStreaming          是否支持流式响应中的 token 用量统计
 * @param maxTokensField                   最大 token 字段名（"max_completion_tokens" 或 "max_tokens"）
 * @param requiresToolResultName           工具结果是否需要 name 字段（某些 Provider 要求）
 * @param requiresAssistantAfterToolResult 工具结果后是否需要插入 assistant 消息（某些 Provider 要求）
 * @param requiresThinkingAsText           thinking 块是否需要转换为文本块（不支持 thinking 类型的 Provider 使用）
 * @param thinkingFormat                   思考参数格式（"openai"/"zai"/"qwen"/"qwen-chat-template"）
 * @param openRouterRouting                OpenRouter 路由偏好（可选）
 * @param vercelGatewayRouting             Vercel AI Gateway 路由偏好（可选）
 * @param supportsStrictMode               是否支持工具定义中的 strict 模式（强制 JSON Schema 严格校验）
 */
// 序列化时忽略值为 null 的字段
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 Java record 定义不可变的 OpenAI 兼容性配置
// 所有字段均为 Boolean 或 String 等引用类型，以支持 null（表示自动检测/默认行为）
public record OpenAICompletionsCompat(
    // 是否支持 store 字段：用于 OpenAI 的存储和检索功能
    @JsonProperty("supportsStore") Boolean supportsStore,
    // 是否支持 developer 角色：某些 Provider 使用 developer 替代 system 角色
    @JsonProperty("supportsDeveloperRole") Boolean supportsDeveloperRole,
    // 是否支持 reasoning_effort 参数：控制模型的思考/推理强度
    @JsonProperty("supportsReasoningEffort") Boolean supportsReasoningEffort,
    // 思考级别映射：将 ThinkingLevel 枚举映射到 Provider 特定的 reasoning_effort 字符串值
    @JsonProperty("reasoningEffortMap") Map<ThinkingLevel, String> reasoningEffortMap,
    // 是否支持流式响应中的 Token 用量统计字段
    @JsonProperty("supportsUsageInStreaming") Boolean supportsUsageInStreaming,
    // 最大 Token 字段名：不同 Provider 使用不同的字段名
    // "max_completion_tokens"（新 OpenAI 规范）或 "max_tokens"（传统规范）
    @JsonProperty("maxTokensField") String maxTokensField,
    // 工具结果消息是否需要 name 字段：某些 Provider（如 Vertex AI）要求工具结果包含 name
    @JsonProperty("requiresToolResultName") Boolean requiresToolResultName,
    // 工具结果后是否需要插入空的 assistant 消息：某些 Provider 的格式要求
    @JsonProperty("requiresAssistantAfterToolResult") Boolean requiresAssistantAfterToolResult,
    // thinking 块是否需要转换为文本块：不支持 thinking 内容类型的 Provider 需要降级处理
    @JsonProperty("requiresThinkingAsText") Boolean requiresThinkingAsText,
    // 思考参数格式：指定思考参数的序列化格式
    // "openai" = OpenAI 标准格式，"zai" = ZAI 格式，"qwen" = 通义千问格式
    @JsonProperty("thinkingFormat") String thinkingFormat,
    // OpenRouter 路由偏好：配置 OpenRouter 的 Provider 路由策略
    @JsonProperty("openRouterRouting") OpenRouterRouting openRouterRouting,
    // Vercel AI Gateway 路由偏好：配置 Vercel Gateway 的 Provider 路由策略
    @JsonProperty("vercelGatewayRouting") VercelGatewayRouting vercelGatewayRouting,
    // 是否支持工具定义中的 strict 模式：强制 JSON Schema 严格模式校验
    @JsonProperty("supportsStrictMode") Boolean supportsStrictMode
) { }
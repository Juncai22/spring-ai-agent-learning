package com.pi.coding.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 动态提供商配置 —— 表示由扩展（Extension）动态注册的 AI 模型提供商配置。
 *
 * <p>扩展可以通过 {@link CodingModelRegistry#registerProvider} 动态注册新的模型提供商，
 * 无需修改核心配置文件。每个提供商配置包含：
 * <ul>
 *   <li>提供商标识和基础 URL</li>
 *   <li>可选的全局请求头和 API Key</li>
 *   <li>该提供商下的模型列表</li>
 * </ul>
 * </p>
 *
 * <p>序列化时，所有 null 字段将被忽略（{@link JsonInclude.Include#NON_NULL}）。</p>
 *
 * @param id       提供商唯一标识符，如 "openai"、"anthropic"
 * @param name     提供商可读名称（可选，用于显示）
 * @param baseUrl  API 请求的基础地址，如 "https://api.openai.com/v1"
 * @param headers  全局请求头，会附加到该提供商下所有模型的请求中
 * @param apiKey   可选的 API Key，如果设置则优先于 {@link AuthStorage} 中的密钥
 * @param models   该提供商下的模型配置列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderConfig(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("baseUrl") String baseUrl,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("apiKey") String apiKey,
    @JsonProperty("models") List<ProviderModelConfig> models
) {}

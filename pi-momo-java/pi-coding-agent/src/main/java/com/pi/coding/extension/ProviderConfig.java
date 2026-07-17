package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 提供者配置 —— 用于通过 registerProvider() 注册模型提供者。
 *
 * <p>定义了一个模型提供者的完整配置，包括 API 端点、认证方式、模型列表和 OAuth 支持。
 * 提供者是模型服务的抽象，代表一个 API 端点服务商（如 Anthropic、OpenAI 等）。
 *
 * <p>配置说明：
 * <ul>
 *   <li>如果只提供 {@code baseUrl}：覆盖现有模型的 API 端点 URL</li>
 *   <li>如果提供 {@code models}：替换该提供者的所有现有模型</li>
 *   <li>如果提供 {@code oauth}：注册 OAuth 提供者，支持 /login 登录流程</li>
 * </ul>
 *
 * @param baseUrl    API 端点的基础 URL
 * @param apiKey     API 密钥或环境变量名
 * @param api        API 类型（如 "anthropic-messages"、"openai-completions"）
 * @param headers    自定义请求头
 * @param authHeader 如果为 true，自动添加 Authorization: Bearer 头（使用解析后的 API Key）
 * @param models     要注册的模型列表（替换该提供者的所有现有模型）
 * @param oauth      OAuth 提供者配置（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderConfig(
    @JsonProperty("baseUrl") String baseUrl,
    @JsonProperty("apiKey") String apiKey,
    @JsonProperty("api") String api,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("authHeader") Boolean authHeader,
    @JsonProperty("models") List<ProviderModelConfig> models,
    OAuthProviderConfig oauth
) {

    /**
     * ProviderConfig 的构建器。
     */
    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String api;
        private Map<String, String> headers;
        private Boolean authHeader;
        private List<ProviderModelConfig> models;
        private OAuthProviderConfig oauth;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }

        public Builder api(String api) { this.api = api; return this; }

        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }

        public Builder authHeader(Boolean authHeader) { this.authHeader = authHeader; return this; }

        public Builder models(List<ProviderModelConfig> models) { this.models = models; return this; }

        public Builder oauth(OAuthProviderConfig oauth) { this.oauth = oauth; return this; }

        public ProviderConfig build() {
            return new ProviderConfig(baseUrl, apiKey, api, headers, authHeader, models, oauth);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

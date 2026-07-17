package com.pi.coding.extension;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.ai.core.types.ModelCost;

import java.util.List;
import java.util.Map;

/**
 * 提供者中的模型配置 —— 定义单个模型在提供者中的详细配置。
 *
 * <p>包含模型的标识信息、能力参数、成本信息和兼容性设置。
 * 用于在注册提供者时定义可用的模型列表。
 *
 * @param id            模型 ID（如 "claude-sonnet-4-20250514"）
 * @param name          显示名称（如 "Claude 4 Sonnet"）
 * @param api           此模型的 API 类型覆盖
 * @param reasoning     模型是否支持扩展思考（extended thinking）
 * @param input         支持的输入类型（如 ["text", "image"]）
 * @param cost          每 Token 成本
 * @param contextWindow 最大上下文窗口大小（Token 数）
 * @param maxTokens     最大输出 Token 数
 * @param headers       此模型的自定义请求头
 * @param compat        OpenAI 兼容性设置
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderModelConfig(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("api") String api,
    @JsonProperty("reasoning") boolean reasoning,
    @JsonProperty("input") List<String> input,
    @JsonProperty("cost") ModelCost cost,
    @JsonProperty("contextWindow") int contextWindow,
    @JsonProperty("maxTokens") int maxTokens,
    @JsonProperty("headers") Map<String, String> headers,
    @JsonProperty("compat") Object compat
) {

    /**
     * ProviderModelConfig 的构建器。
     */
    public static class Builder {
        private String id;
        private String name;
        private String api;
        private boolean reasoning;
        private List<String> input;
        private ModelCost cost;
        private int contextWindow;
        private int maxTokens;
        private Map<String, String> headers;
        private Object compat;

        public Builder id(String id) { this.id = id; return this; }

        public Builder name(String name) { this.name = name; return this; }

        public Builder api(String api) { this.api = api; return this; }

        public Builder reasoning(boolean reasoning) { this.reasoning = reasoning; return this; }

        public Builder input(List<String> input) { this.input = input; return this; }

        public Builder cost(ModelCost cost) { this.cost = cost; return this; }

        public Builder contextWindow(int contextWindow) { this.contextWindow = contextWindow; return this; }

        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        public Builder headers(Map<String, String> headers) { this.headers = headers; return this; }

        public Builder compat(Object compat) { this.compat = compat; return this; }

        public ProviderModelConfig build() {
            return new ProviderModelConfig(id, name, api, reasoning, input, cost, contextWindow, maxTokens, headers, compat);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}

package com.pi.agent.proxy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.ThinkingLevel;

import java.util.Objects;

/**
 * 代理流配置选项 —— 继承 {@link SimpleStreamOptions} 的所有基础字段，
 * 额外包含代理服务器认证令牌（authToken）和代理服务器地址（proxyUrl）信息。
 *
 * <p>当 LLM 调用需要通过代理服务器中转时，使用此配置类指定代理服务器的连接信息。
 * 代理服务器负责管理认证并将请求转发到 LLM 提供商（如 Anthropic、OpenAI 等）。
 *
 * <p>推荐使用 {@link #proxyBuilder()} 工厂方法创建 Builder 实例进行链式构建：
 * <pre>{@code
 * ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
 *     .authToken("my-token")              // 代理服务器认证令牌
 *     .proxyUrl("https://genai.example.com")  // 代理服务器地址
 *     .temperature(0.7)                    // 采样温度（继承自 SimpleStreamOptions）
 *     .maxTokens(4096)                     // 最大输出 Token 数（继承自 SimpleStreamOptions）
 *     .reasoning(ThinkingLevel.HIGH)       // 推理级别（扩展字段）
 *     .build();
 * }</pre>
 *
 * <p>对应 TypeScript 侧的 {@code ProxyStreamOptions} 接口。
 *
 * @see SimpleStreamOptions
 * @see ProxyStream
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProxyStreamOptions extends SimpleStreamOptions {

    /** 代理服务器认证令牌（Bearer Token），用于鉴权。敏感字段，toString 时会脱敏显示为 "***"。 */
    @JsonProperty("authToken")
    private String authToken;

    /** 代理服务器基础 URL，例如 "https://genai.example.com"。流式请求将发送到 {proxyUrl}/api/stream。 */
    @JsonProperty("proxyUrl")
    private String proxyUrl;

    /** Jackson 反序列化用默认无参构造器。 */
    public ProxyStreamOptions() {
    }

    /**
     * 使用 Builder 构造 ProxyStreamOptions 实例。
     *
     * @param builder 包含所有配置项的 Builder 实例
     */
    private ProxyStreamOptions(Builder builder) {
        super(builder, builder.reasoning, builder.thinkingBudgets);
        this.authToken = builder.authToken;
        this.proxyUrl = builder.proxyUrl;
    }

    // ========== Getters ==========

    /**
     * 获取代理服务器认证令牌。
     *
     * @return Bearer Token 字符串
     */
    public String getAuthToken() {
        return authToken;
    }

    /**
     * 获取代理服务器基础 URL。
     *
     * @return 代理服务器地址，如 "https://genai.example.com"
     */
    public String getProxyUrl() {
        return proxyUrl;
    }

    // ========== equals / hashCode / toString ==========

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProxyStreamOptions that)) return false;
        if (!super.equals(o)) return false;
        return Objects.equals(authToken, that.authToken)
            && Objects.equals(proxyUrl, that.proxyUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), authToken, proxyUrl);
    }

    /**
     * 返回配置的字符串表示形式。
     *
     * <p>注意：authToken 字段会进行脱敏处理，仅显示 "***" 而非实际值，
     * 防止敏感信息在日志中泄露。
     */
    @Override
    public String toString() {
        return "ProxyStreamOptions{" +
            "authToken='" + (authToken != null ? "***" : null) + '\'' +  // 脱敏显示
            ", proxyUrl='" + proxyUrl + '\'' +
            ", " + super.toString() +
            '}';
    }

    // ========== Builder ==========

    /**
     * 创建 ProxyStreamOptions 的 Builder 实例。
     *
     * <p>使用链式调用设置各项配置，最后调用 {@link Builder#build()} 构建不可变配置对象。
     *
     * @return 新的 Builder 实例
     */
    public static Builder proxyBuilder() {
        return new Builder();
    }

    /**
     * ProxyStreamOptions 的 Builder 类 —— 继承 {@link AbstractBuilder} 的所有链式方法，
     * 并额外扩展了 reasoning、thinkingBudgets、authToken 和 proxyUrl 四个字段。
     *
     * <p>使用示例：
     * <pre>{@code
     * ProxyStreamOptions opts = ProxyStreamOptions.proxyBuilder()
     *     .authToken("token")
     *     .proxyUrl("https://proxy.example.com")
     *     .temperature(0.5)
     *     .maxTokens(2048)
     *     .build();
     * }</pre>
     */
    public static final class Builder extends AbstractBuilder<Builder> {
        /** 代理服务器认证令牌。 */
        private String authToken;

        /** 代理服务器基础 URL。 */
        private String proxyUrl;

        /** 推理级别（如 off、low、medium、high），用于控制模型是否进行深度思考。 */
        private ThinkingLevel reasoning;

        /** 推理预算控制，用于精细调节思考过程的 Token 分配。 */
        private ThinkingBudgets thinkingBudgets;

        /** 私有构造器，通过 {@link ProxyStreamOptions#proxyBuilder()} 创建实例。 */
        Builder() {
        }

        /**
         * 设置代理服务器认证令牌。
         *
         * @param authToken Bearer Token 字符串，用于代理服务器鉴权
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder authToken(String authToken) {
            this.authToken = authToken;
            return this;
        }

        /**
         * 设置代理服务器基础 URL。
         *
         * @param proxyUrl 代理服务器地址，如 "https://genai.example.com"
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder proxyUrl(String proxyUrl) {
            this.proxyUrl = proxyUrl;
            return this;
        }

        /**
         * 设置推理级别。
         *
         * <p>推理级别控制模型在生成回复前是否进行深度思考。
         * 可选值包括：{@link ThinkingLevel#OFF}（关闭）、
         * {@link ThinkingLevel#LOW}（低）、{@link ThinkingLevel#MEDIUM}（中）、
         * {@link ThinkingLevel#HIGH}（高）。
         * 启用推理会消耗更多 Token，但能提升复杂问题的回答质量。
         *
         * @param reasoning 推理级别枚举值
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder reasoning(ThinkingLevel reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        /**
         * 设置推理预算。
         *
         * <p>推理预算用于精细控制模型思考过程的 Token 分配上限，
         * 适用于需要精确控制推理开销的场景。
         *
         * @param thinkingBudgets 推理预算配置
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder thinkingBudgets(ThinkingBudgets thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        /**
         * 构建 ProxyStreamOptions 实例。
         *
         * <p>调用此方法前，请确保已通过链式调用设置必要的字段。
         * authToken 和 proxyUrl 为必填字段，构建时不会进行空值校验，
         * 但使用时会在 {@link ProxyStream#streamProxy} 方法中生效。
         *
         * @return 构建完成的 ProxyStreamOptions 实例
         */
        @Override
        public ProxyStreamOptions build() {
            return new ProxyStreamOptions(this);
        }
    }
}

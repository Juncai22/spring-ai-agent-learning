package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * 流式调用的基础选项。
 *
 * <p>包含 temperature、maxTokens、apiKey 等所有 Provider 共享的调用参数。
 * 使用 Builder 模式构建，支持继承（{@link SimpleStreamOptions}）。
 *
 * <p>注意：{@code signal}（取消机制）将在 Task 3.4 中添加。
 *
 * <p>对应 TypeScript 中的 {@code StreamOptions} 接口。
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 class 而非 record，以支持继承（SimpleStreamOptions 继承此类）
public class StreamOptions {

    /** 温度参数，控制生成结果的随机性（0.0 ~ 2.0） */
    @JsonProperty("temperature")
    private Double temperature;

    /** 最大输出 Token 数 */
    @JsonProperty("maxTokens")
    private Integer maxTokens;

    /** API 密钥，用于认证 */
    @JsonProperty("apiKey")
    private String apiKey;

    /** 提示缓存保留策略 */
    @JsonProperty("cacheRetention")
    private CacheRetention cacheRetention;

    /** 会话 ID，用于多轮对话跟踪 */
    @JsonProperty("sessionId")
    private String sessionId;

    /** 自定义 HTTP 头 */
    @JsonProperty("headers")
    private Map<String, String> headers;

    /** 流式传输协议 */
    @JsonProperty("transport")
    private Transport transport;

    /** 请求重试最大延迟（毫秒） */
    @JsonProperty("maxRetryDelayMs")
    private Integer maxRetryDelayMs;

    /** 请求元数据 */
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    /** 请求载荷拦截器回调，不参与 JSON 序列化。 */
    @JsonIgnore
    private PayloadInterceptor onPayload;

    /** 取消信号，用于中断正在进行的请求，不参与 JSON 序列化。 */
    @JsonIgnore
    private CancellationSignal signal;

    /** Jackson 反序列化用默认构造器。 */
    public StreamOptions() {
    }

    /** Builder 内部构造器。 */
    protected StreamOptions(AbstractBuilder<?> builder) {
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.apiKey = builder.apiKey;
        this.cacheRetention = builder.cacheRetention;
        this.sessionId = builder.sessionId;
        this.headers = builder.headers;
        this.transport = builder.transport;
        this.maxRetryDelayMs = builder.maxRetryDelayMs;
        this.metadata = builder.metadata;
        this.onPayload = builder.onPayload;
        this.signal = builder.signal;
    }

    // --- Getters ---

    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getApiKey() { return apiKey; }
    public CacheRetention getCacheRetention() { return cacheRetention; }
    public String getSessionId() { return sessionId; }
    public Map<String, String> getHeaders() { return headers; }
    public Transport getTransport() { return transport; }
    public Integer getMaxRetryDelayMs() { return maxRetryDelayMs; }
    public Map<String, Object> getMetadata() { return metadata; }
    public PayloadInterceptor getOnPayload() { return onPayload; }
    public CancellationSignal getSignal() { return signal; }

    // --- equals / hashCode / toString ---

    @Override
    public boolean equals(Object o) {
        // Step 1: 自引用检查，同一对象直接返回 true
        if (this == o) return true;
        // Step 2: 类型检查，确保是比较对象是 StreamOptions 类型
        if (!(o instanceof StreamOptions that)) return false;
        // Step 3: 逐字段比较
        // 注意：onPayload 和 signal 是函数引用和信号对象，不参与 equals 比较
        // 原因：函数引用无法正确比较相等性，信号对象是运行时状态
        return Objects.equals(temperature, that.temperature)
            && Objects.equals(maxTokens, that.maxTokens)
            && Objects.equals(apiKey, that.apiKey)
            && cacheRetention == that.cacheRetention
            && Objects.equals(sessionId, that.sessionId)
            && Objects.equals(headers, that.headers)
            && transport == that.transport
            && Objects.equals(maxRetryDelayMs, that.maxRetryDelayMs)
            && Objects.equals(metadata, that.metadata);
        // onPayload 为函数引用，不参与 equals 比较
    }

    @Override
    public int hashCode() {
        // 计算哈希值，排除 onPayload 和 signal
        // 原因：与 equals 保持一致，不参与比较的字段也不参与哈希计算
        return Objects.hash(temperature, maxTokens, apiKey, cacheRetention,
                sessionId, headers, transport, maxRetryDelayMs, metadata);
    }

    @Override
    public String toString() {
        // 返回便于调试的字符串表示
        // 注意：apiKey 显示为 "***" 而非明文，避免敏感信息泄露
        // onPayload 和 signal 显示为 "<set>" 或 "null" 表示是否存在
        return "StreamOptions{" +
            "temperature=" + temperature +
            ", maxTokens=" + maxTokens +
            ", apiKey='" + (apiKey != null ? "***" : null) + '\'' +
            ", cacheRetention=" + cacheRetention +
            ", sessionId='" + sessionId + '\'' +
            ", headers=" + headers +
            ", transport=" + transport +
            ", maxRetryDelayMs=" + maxRetryDelayMs +
            ", metadata=" + metadata +
            ", onPayload=" + (onPayload != null ? "<set>" : "null") +
            ", signal=" + (signal != null ? "<set>" : "null") +
            '}';
    }

    // --- Builder ---

    /** 创建 StreamOptions 的 Builder。 */
    public static Builder builder() {
        return new Builder();
    }

    /** StreamOptions 的具体 Builder。 */
    public static final class Builder extends AbstractBuilder<Builder> {
        Builder() {
        }

        @Override
        public StreamOptions build() {
            return new StreamOptions(this);
        }
    }

    /**
     * 泛型 Builder 基类，供子类（如 {@link SimpleStreamOptions}）继承扩展。
     *
     * @param <B> Builder 自身类型，用于流式 API 的类型安全返回
     */
    @SuppressWarnings("unchecked")
    public abstract static class AbstractBuilder<B extends AbstractBuilder<B>> {
        // Step 1: 定义所有可选字段及其默认值
        // 所有字段默认为 null，表示使用 Provider 的默认值
        Double temperature;           // 温度参数
        Integer maxTokens;            // 最大输出 Token 数
        String apiKey;                // API 密钥
        CacheRetention cacheRetention; // 缓存保留策略
        String sessionId;             // 会话 ID
        Map<String, String> headers;  // 自定义 HTTP 头
        Transport transport;          // 传输协议
        Integer maxRetryDelayMs;      // 重试最大延迟
        Map<String, Object> metadata; // 请求元数据
        PayloadInterceptor onPayload; // 请求载荷拦截器
        CancellationSignal signal;    // 取消信号

        protected AbstractBuilder() {
        }

        /** 设置温度参数 */
        public B temperature(Double temperature) {
            this.temperature = temperature;
            return (B) this;
        }

        /** 设置最大输出 Token 数 */
        public B maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return (B) this;
        }

        /** 设置 API 密钥 */
        public B apiKey(String apiKey) {
            this.apiKey = apiKey;
            return (B) this;
        }

        /** 设置缓存保留策略 */
        public B cacheRetention(CacheRetention cacheRetention) {
            this.cacheRetention = cacheRetention;
            return (B) this;
        }

        /** 设置会话 ID */
        public B sessionId(String sessionId) {
            this.sessionId = sessionId;
            return (B) this;
        }

        /** 设置自定义 HTTP 头 */
        public B headers(Map<String, String> headers) {
            this.headers = headers;
            return (B) this;
        }

        /** 设置传输协议 */
        public B transport(Transport transport) {
            this.transport = transport;
            return (B) this;
        }

        /** 设置重试最大延迟（毫秒） */
        public B maxRetryDelayMs(Integer maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return (B) this;
        }

        /** 设置请求元数据 */
        public B metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return (B) this;
        }

        /** 设置请求载荷拦截器回调 */
        public B onPayload(PayloadInterceptor onPayload) {
            this.onPayload = onPayload;
            return (B) this;
        }

        /** 设置取消信号 */
        public B signal(CancellationSignal signal) {
            this.signal = signal;
            return (B) this;
        }

        /** 构建选项实例。 */
        public abstract StreamOptions build();
    }
}
package com.pi.ai.core.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * LLM assistant response message. Uses a mutable class (not record) because
 * content, usage, and other fields are accumulated incrementally during streaming.
 * LLM 助手的响应消息。使用可变类（而非 record）是因为在流式场景中，
 * 内容、用量等信息是逐步累积的。
 *
 * <p>Construct via {@link Builder}:
 * 通过 {@link Builder} 构建：
 * <pre>{@code
 * AssistantMessage msg = AssistantMessage.builder()
 *     .content(List.of(new TextContent("hello")))
 *     .api("anthropic-messages")
 *     .provider("anthropic")
 *     .model("claude-3-opus")
 *     .usage(usage)
 *     .stopReason(StopReason.STOP)
 *     .timestamp(System.currentTimeMillis())
 *     .build();
 * }</pre>
 */
// 序列化时忽略值为 null 的字段，减少 JSON 体积
@JsonInclude(JsonInclude.Include.NON_NULL)
// 使用 final class 而非 record，因为流式场景中需要 setter 方法逐步累加字段值
public final class AssistantMessage implements Message {

    /** 角色标识符，固定为 "assistant" */
    // 角色字段，固定为 "assistant"，声明为 final 不可变
    @JsonProperty("role")
    private final String role = "assistant";

    /** 响应内容块列表（文本、思考过程、工具调用） */
    // 内容块列表，可包含 TextContent、ThinkingContent、ToolCall
    @JsonProperty("content")
    private List<AssistantContentBlock> content;

    /** 使用的 API 协议标识（如 "anthropic-messages"、"openai-completions"） */
    // 标识此次响应使用的 API 协议，用于区分不同 Provider 的响应格式
    @JsonProperty("api")
    private String api;

    /** 服务提供商名称（如 "anthropic"、"openai"） */
    // 标识此次响应的服务提供商，用于日志和费用统计
    @JsonProperty("provider")
    private String provider;

    /** 模型名称（如 "claude-sonnet-4-20250514"） */
    // 实际响应的模型名称，可能与请求时指定的模型不同（如降级情况）
    @JsonProperty("model")
    private String model;

    /** Token 用量统计 */
    // 包含输入/输出/缓存的 Token 计数和费用明细
    @JsonProperty("usage")
    private Usage usage;

    /** 停止原因 */
    // 指示 LLM 为何停止生成：正常结束、达长度限制、工具调用、错误、被取消
    @JsonProperty("stopReason")
    private StopReason stopReason;

    /** 错误信息（当 stopReason 为 ERROR 时包含） */
    // 当 LLM 返回错误时，此字段包含详细的错误描述信息
    @JsonProperty("errorMessage")
    private String errorMessage;

    /** 消息创建时间戳（毫秒） */
    @JsonProperty("timestamp")
    private long timestamp;

    /**
     * Default constructor for Jackson deserialization.
     * 默认构造器，供 Jackson 反序列化使用。
     */
    // 无参构造器：Jackson 反序列化时需要默认构造器，然后通过 setter 或反射设值
    public AssistantMessage() {
    }

    // Step 1: Builder 模式构造器，从 Builder 中复制所有字段值
    // 原因：使用 Builder 模式提供链式调用，避免构造器参数过多导致可读性差
    private AssistantMessage(Builder builder) {
        this.content = builder.content;
        this.api = builder.api;
        this.provider = builder.provider;
        this.model = builder.model;
        this.usage = builder.usage;
        this.stopReason = builder.stopReason;
        this.errorMessage = builder.errorMessage;
        this.timestamp = builder.timestamp;
    }

    // --- Message interface --- 实现 Message 接口

    @Override
    public String role() {
        // 返回固定值 "assistant"
        return role;
    }

    @Override
    public long timestamp() {
        // 返回消息创建时间戳
        return timestamp;
    }

    // --- Getters --- 字段访问方法

    public String getRole() {
        return role;
    }

    public List<AssistantContentBlock> getContent() {
        return content;
    }

    public String getApi() {
        return api;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Usage getUsage() {
        return usage;
    }

    public StopReason getStopReason() {
        return stopReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // --- Setters for streaming mutation --- 流式累加用的 Setter 方法

    // Step 1: 流式场景中，响应内容块逐步到达，需要通过 setter 更新
    // 原因：使用 mutable class 而非 record，正是为了支持这种逐步累加的模式
    public void setContent(List<AssistantContentBlock> content) {
        this.content = content;
    }

    // Step 2: 流结束后设置最终的用量统计
    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    // Step 3: 流结束时设置停止原因
    public void setStopReason(StopReason stopReason) {
        this.stopReason = stopReason;
    }

    // Step 4: 当发生错误时设置错误信息
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // Step 5: 设置时间戳（通常只在首次创建时设置一次）
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    // --- equals / hashCode --- 相等性判断与哈希计算

    @Override
    public boolean equals(Object o) {
        // Step 1: 自引用检查，如果指向同一对象则直接返回 true
        if (this == o) return true;
        // Step 2: 类型检查，确保比较对象是 AssistantMessage 类型
        // 使用 instanceof 模式匹配（Java 16+），同时完成类型转换
        if (!(o instanceof AssistantMessage that)) return false;
        // Step 3: 逐字段比较，所有字段都必须相等
        // 注意：timestamp 是基本类型，用 == 比较；其他引用类型用 Objects.equals
        return timestamp == that.timestamp
            && Objects.equals(role, that.role)
            && Objects.equals(content, that.content)
            && Objects.equals(api, that.api)
            && Objects.equals(provider, that.provider)
            && Objects.equals(model, that.model)
            && Objects.equals(usage, that.usage)
            && stopReason == that.stopReason
            && Objects.equals(errorMessage, that.errorMessage);
    }

    @Override
    public int hashCode() {
        // 使用 Objects.hash 计算哈希值，包含所有非静态字段
        // 注意：哈希值参与计算的字段必须与 equals 中使用的字段一致
        return Objects.hash(role, content, api, provider, model, usage, stopReason, errorMessage, timestamp);
    }

    @Override
    public String toString() {
        // 返回便于调试的字符串表示，包含所有字段的值
        return "AssistantMessage{" +
            "role='" + role + '\'' +
            ", content=" + content +
            ", api='" + api + '\'' +
            ", provider='" + provider + '\'' +
            ", model='" + model + '\'' +
            ", usage=" + usage +
            ", stopReason=" + stopReason +
            ", errorMessage='" + errorMessage + '\'' +
            ", timestamp=" + timestamp +
            '}';
    }

    // --- Builder --- 构建器模式

    // Step 1: 创建 Builder 的静态工厂方法
    // 原因：使用静态工厂方法而非直接 new Builder()，提供更清晰的 API 语义
    public static Builder builder() {
        return new Builder();
    }

    // Step 1: Builder 内部类，用于构建 AssistantMessage 实例
    // 原因：Builder 模式可以优雅地处理多个可选参数，避免 telescoping constructor 反模式
    public static final class Builder {
        // Step 2: 初始化 content 为空列表，确保 build() 后 content 不会为 null
        private List<AssistantContentBlock> content = new ArrayList<>();
        private String api;           // API 协议标识（可选）
        private String provider;      // 服务提供商（可选）
        private String model;         // 模型名称（可选）
        private Usage usage;          // 用量统计（可选，流结束后才设置）
        private StopReason stopReason; // 停止原因（可选）
        private String errorMessage;  // 错误信息（可选）
        private long timestamp;       // 创建时间戳

        // 私有构造器，防止外部直接实例化
        private Builder() {
        }

        /** 设置响应内容块列表 */
        public Builder content(List<AssistantContentBlock> content) {
            this.content = content;
            return this;
        }

        /** 设置 API 协议标识 */
        public Builder api(String api) {
            this.api = api;
            return this;
        }

        /** 设置服务提供商 */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /** 设置模型名称 */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** 设置 Token 用量统计 */
        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        /** 设置停止原因 */
        public Builder stopReason(StopReason stopReason) {
            this.stopReason = stopReason;
            return this;
        }

        /** 设置错误信息 */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /** 设置创建时间戳 */
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /** 构建 AssistantMessage 实例 */
        public AssistantMessage build() {
            return new AssistantMessage(this);
        }
    }
}
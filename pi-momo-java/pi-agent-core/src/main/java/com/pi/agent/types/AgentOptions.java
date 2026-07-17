package com.pi.agent.types;

import com.pi.agent.config.AfterToolCallHook;
import com.pi.agent.config.BeforeToolCallHook;
import com.pi.agent.config.ConvertToLlmFunction;
import com.pi.agent.config.GetApiKeyFunction;
import com.pi.agent.config.StreamFn;
import com.pi.agent.config.TransformContextFunction;
import com.pi.ai.core.types.PayloadInterceptor;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.Transport;

/**
 * 传递给 Agent 构造函数的配置对象。
 *
 * <p>所有字段都是可选的（可为 null）。使用 {@link #builder()} 构建实例：
 * <pre>{@code
 * AgentOptions opts = AgentOptions.builder()
 *     .steeringMode(QueueMode.ALL)
 *     .toolExecution(ToolExecutionMode.SEQUENTIAL)
 *     .maxRetryDelayMs(30000)
 *     .build();
 * }</pre>
 *
 * <p><b>验证需求：23.1, 23.2</b>
 */
public class AgentOptions {

    /** 初始状态的局部配置 */
    private final AgentState initialState;

    /** 将 AgentMessage 转换为 LLM Message 的回调函数 */
    private final ConvertToLlmFunction convertToLlm;

    /** 转换上下文的回调函数 */
    private final TransformContextFunction transformContext;

    /** 引导（steering）消息的出队模式 */
    private final QueueMode steeringMode;

    /** 跟进（follow-up）消息的出队模式 */
    private final QueueMode followUpMode;

    /** 自定义流式处理函数 */
    private final StreamFn streamFn;

    /** 会话标识符 */
    private final String sessionId;

    /** 获取 API Key 的回调函数 */
    private final GetApiKeyFunction getApiKey;

    /** 请求载荷拦截器 */
    private final PayloadInterceptor onPayload;

    /** 思考预算配置 */
    private final ThinkingBudgets thinkingBudgets;

    /** 传输层配置 */
    private final Transport transport;

    /** 最大重试延迟时间（毫秒） */
    private final Integer maxRetryDelayMs;

    /** 工具执行模式（顺序执行/并行执行） */
    private final ToolExecutionMode toolExecution;

    /** 工具执行前的钩子函数 */
    private final BeforeToolCallHook beforeToolCall;

    /** 工具执行后的钩子函数 */
    private final AfterToolCallHook afterToolCall;

    private AgentOptions(Builder builder) {
        this.initialState = builder.initialState;
        this.convertToLlm = builder.convertToLlm;
        this.transformContext = builder.transformContext;
        this.steeringMode = builder.steeringMode;
        this.followUpMode = builder.followUpMode;
        this.streamFn = builder.streamFn;
        this.sessionId = builder.sessionId;
        this.getApiKey = builder.getApiKey;
        this.onPayload = builder.onPayload;
        this.thinkingBudgets = builder.thinkingBudgets;
        this.transport = builder.transport;
        this.maxRetryDelayMs = builder.maxRetryDelayMs;
        this.toolExecution = builder.toolExecution;
        this.beforeToolCall = builder.beforeToolCall;
        this.afterToolCall = builder.afterToolCall;
    }

    // --- Getter 方法 ---

    /** 返回部分初始状态，或 {@code null}。 */
    public AgentState getInitialState() { return initialState; }

    /** 返回 convertToLlm 回调函数，或 {@code null}。 */
    public ConvertToLlmFunction getConvertToLlm() { return convertToLlm; }

    /** 返回 transformContext 回调函数，或 {@code null}。 */
    public TransformContextFunction getTransformContext() { return transformContext; }

    /** 返回引导消息的出队模式，或 {@code null}。 */
    public QueueMode getSteeringMode() { return steeringMode; }

    /** 返回跟进消息的出队模式，或 {@code null}。 */
    public QueueMode getFollowUpMode() { return followUpMode; }

    /** 返回自定义流式处理函数，或 {@code null}。 */
    public StreamFn getStreamFn() { return streamFn; }

    /** 返回会话标识符，或 {@code null}。 */
    public String getSessionId() { return sessionId; }

    /** 返回获取 API Key 的回调函数，或 {@code null}。 */
    public GetApiKeyFunction getGetApiKey() { return getApiKey; }

    /** 返回请求载荷拦截器，或 {@code null}。 */
    public PayloadInterceptor getOnPayload() { return onPayload; }

    /** 返回思考预算配置，或 {@code null}。 */
    public ThinkingBudgets getThinkingBudgets() { return thinkingBudgets; }

    /** 返回传输层配置，或 {@code null}。 */
    public Transport getTransport() { return transport; }

    /** 返回最大重试延迟时间（毫秒），或 {@code null}。 */
    public Integer getMaxRetryDelayMs() { return maxRetryDelayMs; }

    /** 返回工具执行模式，或 {@code null}。 */
    public ToolExecutionMode getToolExecution() { return toolExecution; }

    /** 返回工具执行前的钩子函数，或 {@code null}。 */
    public BeforeToolCallHook getBeforeToolCall() { return beforeToolCall; }

    /** 返回工具执行后的钩子函数，或 {@code null}。 */
    public AfterToolCallHook getAfterToolCall() { return afterToolCall; }

    // --- Builder ---

    /** 创建一个新的 {@link Builder} 用于构建 {@link AgentOptions}。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AgentOptions} 的构建器。所有字段都是可选的。
     */
    public static final class Builder {

        private AgentState initialState;
        private ConvertToLlmFunction convertToLlm;
        private TransformContextFunction transformContext;
        private QueueMode steeringMode;
        private QueueMode followUpMode;
        private StreamFn streamFn;
        private String sessionId;
        private GetApiKeyFunction getApiKey;
        private PayloadInterceptor onPayload;
        private ThinkingBudgets thinkingBudgets;
        private Transport transport;
        private Integer maxRetryDelayMs;
        private ToolExecutionMode toolExecution;
        private BeforeToolCallHook beforeToolCall;
        private AfterToolCallHook afterToolCall;

        Builder() {}

        /** 设置初始状态的局部配置。 */
        public Builder initialState(AgentState initialState) {
            this.initialState = initialState;
            return this;
        }

        /** 设置将 AgentMessage 转换为 LLM Message 的回调函数。 */
        public Builder convertToLlm(ConvertToLlmFunction convertToLlm) {
            this.convertToLlm = convertToLlm;
            return this;
        }

        /** 设置转换上下文的回调函数。 */
        public Builder transformContext(TransformContextFunction transformContext) {
            this.transformContext = transformContext;
            return this;
        }

        /** 设置引导消息的出队模式。 */
        public Builder steeringMode(QueueMode steeringMode) {
            this.steeringMode = steeringMode;
            return this;
        }

        /** 设置跟进消息的出队模式。 */
        public Builder followUpMode(QueueMode followUpMode) {
            this.followUpMode = followUpMode;
            return this;
        }

        /** 设置自定义流式处理函数。 */
        public Builder streamFn(StreamFn streamFn) {
            this.streamFn = streamFn;
            return this;
        }

        /** 设置会话标识符。 */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /** 设置获取 API Key 的回调函数。 */
        public Builder getApiKey(GetApiKeyFunction getApiKey) {
            this.getApiKey = getApiKey;
            return this;
        }

        /** 设置请求载荷拦截器。 */
        public Builder onPayload(PayloadInterceptor onPayload) {
            this.onPayload = onPayload;
            return this;
        }

        /** 设置思考预算配置。 */
        public Builder thinkingBudgets(ThinkingBudgets thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        /** 设置传输层配置。 */
        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /** 设置最大重试延迟时间（毫秒）。 */
        public Builder maxRetryDelayMs(Integer maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return this;
        }

        /** 设置工具执行模式。 */
        public Builder toolExecution(ToolExecutionMode toolExecution) {
            this.toolExecution = toolExecution;
            return this;
        }

        /** 设置工具执行前的钩子函数。 */
        public Builder beforeToolCall(BeforeToolCallHook beforeToolCall) {
            this.beforeToolCall = beforeToolCall;
            return this;
        }

        /** 设置工具执行后的钩子函数。 */
        public Builder afterToolCall(AfterToolCallHook afterToolCall) {
            this.afterToolCall = afterToolCall;
            return this;
        }

        /** 构建 {@link AgentOptions} 实例。 */
        public AgentOptions build() {
            return new AgentOptions(this);
        }
    }
}
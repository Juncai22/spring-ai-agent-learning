package com.pi.agent.config;

import com.pi.agent.types.ToolExecutionMode;
import com.pi.ai.core.types.CacheRetention;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.PayloadInterceptor;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.ThinkingLevel;
import com.pi.ai.core.types.Transport;

import java.util.Map;

/**
 * Agent 主循环的配置类，通过组合方式将 {@link SimpleStreamOptions}（流式调用参数）
 * 与 Agent 专属字段（如模型、回调函数、工具执行钩子等）整合在一起。
 *
 * <p>推荐使用 {@link #builder()} 构建实例：
 * <pre>{@code
 * AgentLoopConfig config = AgentLoopConfig.builder()
 *     .model(myModel)
 *     .convertToLlm(msgs -> ...)
 *     .temperature(0.7)
 *     .maxTokens(4096)
 *     .toolExecution(ToolExecutionMode.PARALLEL)
 *     .build();
 * }</pre>
 *
 * <p><b>验证的需求：13.1, 13.2, 13.3, 13.4, 13.5, 13.6, 13.7, 13.8, 13.9, 13.10, 13.11</b>
 *
 * <p>该类是整个 Agent 循环的核心配置持有者，它定义了 Agent 与 LLM 交互时的全部行为参数，
 * 包括：目标模型、消息转换回调、上下文转换回调、API Key 动态获取、引导消息注入、
 * 后续消息轮询、工具执行模式以及工具调用前后的钩子函数。
 */
public class AgentLoopConfig {

    /** 组合的 SimpleStreamOptions 实例，包含流式调用所需的全部参数（Req 13.1） */
    private final SimpleStreamOptions streamOptions;

    /** 目标 LLM 模型，指定 Agent 使用哪个模型进行推理（Req 13.2） */
    private final Model model;

    /** 将 Agent 层消息转换为 LLM 层消息的回调函数（Req 13.3, 13.4） */
    private final ConvertToLlmFunction convertToLlm;

    /** 对 Agent 消息列表进行预处理/转换的回调函数，用于上下文窗口管理等场景（Req 13.5, 13.6） */
    private final TransformContextFunction transformContext;

    /** 动态获取 API Key 的回调函数，支持按 provider 动态切换认证凭据（Req 13.7） */
    private final GetApiKeyFunction getApiKey;

    /** 获取引导消息（steering messages）的回调函数，用于在每轮工具执行后注入额外上下文（Req 13.8） */
    private final GetSteeringMessagesFunction getSteeringMessages;

    /** 获取后续消息（follow-up messages）的回调函数，用于在无更多工具调用时触发新轮次（Req 13.9） */
    private final GetFollowUpMessagesFunction getFollowUpMessages;

    /** 工具执行模式，决定是否并行执行多个工具调用，默认为并行执行（Req 13.10） */
    private final ToolExecutionMode toolExecution;

    /** 工具执行前钩子，可在工具调用前进行校验、拦截或审计（Req 13.11） */
    private final BeforeToolCallHook beforeToolCall;

    /** 工具执行后钩子，可对工具执行结果进行后处理或覆盖（Req 13.11） */
    private final AfterToolCallHook afterToolCall;

    private AgentLoopConfig(Builder builder) {
        this.streamOptions = builder.buildStreamOptions();
        this.model = builder.model;
        this.convertToLlm = builder.convertToLlm;
        this.transformContext = builder.transformContext;
        this.getApiKey = builder.getApiKey;
        this.getSteeringMessages = builder.getSteeringMessages;
        this.getFollowUpMessages = builder.getFollowUpMessages;
        this.toolExecution = builder.toolExecution != null
                ? builder.toolExecution
                : ToolExecutionMode.PARALLEL;
        this.beforeToolCall = builder.beforeToolCall;
        this.afterToolCall = builder.afterToolCall;
    }

    // ========== Getter 方法 ==========

    /**
     * 获取组合的流式调用选项实例。
     * @return SimpleStreamOptions 实例，包含 temperature、maxTokens 等流式参数
     */
    public SimpleStreamOptions getStreamOptions() { return streamOptions; }

    /**
     * 获取目标 LLM 模型。
     * @return 当前配置的模型实例
     */
    public Model getModel() { return model; }

    /**
     * 获取消息转换回调函数。
     * @return 将 AgentMessage 列表转换为 LLM Message 列表的函数
     */
    public ConvertToLlmFunction getConvertToLlm() { return convertToLlm; }

    /**
     * 获取上下文转换回调函数（可能为 null）。
     * @return 用于预处理/转换消息列表的函数，未配置时返回 null
     */
    public TransformContextFunction getTransformContext() { return transformContext; }

    /**
     * 获取 API Key 动态解析回调函数（可能为 null）。
     * @return 用于按 provider 动态获取 API Key 的函数，未配置时返回 null
     */
    public GetApiKeyFunction getGetApiKey() { return getApiKey; }

    /**
     * 获取引导消息回调函数（可能为 null）。
     * @return 用于获取引导消息的函数，未配置时返回 null
     */
    public GetSteeringMessagesFunction getGetSteeringMessages() { return getSteeringMessages; }

    /**
     * 获取后续消息回调函数（可能为 null）。
     * @return 用于获取后续消息的函数，未配置时返回 null
     */
    public GetFollowUpMessagesFunction getGetFollowUpMessages() { return getFollowUpMessages; }

    /**
     * 获取工具执行模式。
     * @return 工具执行模式，默认为 {@link ToolExecutionMode#PARALLEL}（并行执行）
     */
    public ToolExecutionMode getToolExecution() { return toolExecution; }

    /**
     * 获取工具执行前钩子（可能为 null）。
     * @return 工具执行前被调用的钩子函数，未配置时返回 null
     */
    public BeforeToolCallHook getBeforeToolCall() { return beforeToolCall; }

    /**
     * 获取工具执行后钩子（可能为 null）。
     * @return 工具执行后被调用的钩子函数，未配置时返回 null
     */
    public AfterToolCallHook getAfterToolCall() { return afterToolCall; }

    // ========== Builder 构建器 ==========

    /**
     * 创建 {@link AgentLoopConfig} 的构建器实例。
     * @return 新的 Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@link AgentLoopConfig} 的构建器（Builder）。
     *
     * <p>同时暴露 {@link SimpleStreamOptions} 的所有字段设置方法（temperature、maxTokens、apiKey、
     * reasoning、thinkingBudgets 等）以及 Agent 专属字段设置方法（model、convertToLlm、钩子等）。
     *
     * <p>构建器内部会从流式相关字段构造一个 {@link SimpleStreamOptions} 实例，
     * 并将其传递给最终的 AgentLoopConfig 对象。
     *
     * <p>使用示例：
     * <pre>{@code
     * AgentLoopConfig config = AgentLoopConfig.builder()
     *     .model(Model.CLAUDE_SONNET_4_5)
     *     .convertToLlm(DefaultConvertToLlm.INSTANCE)
     *     .temperature(0.7)
     *     .maxTokens(4096)
     *     .toolExecution(ToolExecutionMode.PARALLEL)
     *     .build();
     * }</pre>
     */
    public static final class Builder {

        // ========== SimpleStreamOptions 字段 ==========

        /** 温度参数，控制 LLM 输出的随机性，值越大输出越多样 */
        private Double temperature;

        /** 每次 LLM 调用返回的最大 token 数 */
        private Integer maxTokens;

        /** LLM API 调用的 API Key */
        private String apiKey;

        /** 缓存保留策略，用于控制 Prompt Caching 行为 */
        private CacheRetention cacheRetention;

        /** 会话标识，用于多轮对话的上下文关联 */
        private String sessionId;

        /** 自定义 HTTP 请求头 */
        private Map<String, String> headers;

        /** 传输层协议，如 HTTP/SSE 等 */
        private Transport transport;

        /** 最大重试延迟（毫秒），控制请求失败时的退避策略 */
        private Integer maxRetryDelayMs;

        /** 元数据，用于附加自定义信息 */
        private Map<String, Object> metadata;

        /** 请求载荷拦截器，用于在发送前检查和修改请求 */
        private PayloadInterceptor onPayload;

        /** 取消信号，用于协程式取消正在进行的请求 */
        private CancellationSignal signal;

        /** 推理/思考级别，控制模型是否使用扩展思考能力 */
        private ThinkingLevel reasoning;

        /** 思考预算，控制模型思考过程可消耗的 token 数量 */
        private ThinkingBudgets thinkingBudgets;

        // ========== Agent 专属字段 ==========

        /** 目标 LLM 模型 */
        private Model model;

        /** 消息转换回调：AgentMessage -> LLM Message */
        private ConvertToLlmFunction convertToLlm;

        /** 上下文转换回调：预处理消息列表 */
        private TransformContextFunction transformContext;

        /** API Key 动态获取回调 */
        private GetApiKeyFunction getApiKey;

        /** 引导消息获取回调 */
        private GetSteeringMessagesFunction getSteeringMessages;

        /** 后续消息获取回调 */
        private GetFollowUpMessagesFunction getFollowUpMessages;

        /** 工具执行模式（并行/串行） */
        private ToolExecutionMode toolExecution;

        /** 工具执行前钩子 */
        private BeforeToolCallHook beforeToolCall;

        /** 工具执行后钩子 */
        private AfterToolCallHook afterToolCall;

        /** 私有构造方法，仅允许通过 {@link AgentLoopConfig#builder()} 创建 */
        Builder() {}

        // ========== SimpleStreamOptions 字段设置方法 ==========

        /**
         * 设置温度参数，控制 LLM 输出的随机性/创造性。
         * @param temperature 温度值，范围通常在 0.0 ~ 2.0，值越大输出越多样
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 设置每次 LLM 调用返回的最大 token 数。
         * @param maxTokens 最大 token 数量
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * 设置 LLM API 调用的 API Key。
         * @param apiKey API Key 字符串
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置缓存保留策略，用于 Prompt Caching 的场景。
         * @param cacheRetention 缓存保留策略
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder cacheRetention(CacheRetention cacheRetention) {
            this.cacheRetention = cacheRetention;
            return this;
        }

        /**
         * 设置会话 ID，用于多轮对话的上下文关联。
         * @param sessionId 会话标识字符串
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        /**
         * 设置自定义 HTTP 请求头。
         * @param headers 请求头键值对映射
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * 设置传输层协议。
         * @param transport 传输协议类型，如 HTTP/SSE
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * 设置最大重试延迟（毫秒），控制请求失败时的退避策略上限。
         * @param maxRetryDelayMs 最大重试延迟毫秒数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder maxRetryDelayMs(Integer maxRetryDelayMs) {
            this.maxRetryDelayMs = maxRetryDelayMs;
            return this;
        }

        /**
         * 设置附加元数据。
         * @param metadata 元数据键值对映射
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * 设置请求载荷拦截器，用于在请求发送前检查和修改载荷。
         * @param onPayload 载荷拦截器实例
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder onPayload(PayloadInterceptor onPayload) {
            this.onPayload = onPayload;
            return this;
        }

        /**
         * 设置取消信号，用于协程式取消正在进行的流式请求。
         * @param signal 取消信号实例
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder signal(CancellationSignal signal) {
            this.signal = signal;
            return this;
        }

        /**
         * 设置推理/思考级别，控制模型是否使用扩展思考能力。
         * @param reasoning 思考级别（如 OFF、ON、HIGH 等）
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder reasoning(ThinkingLevel reasoning) {
            this.reasoning = reasoning;
            return this;
        }

        /**
         * 设置思考预算，控制模型在思考过程中可消耗的 token 数量。
         * @param thinkingBudgets 思考预算配置
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder thinkingBudgets(ThinkingBudgets thinkingBudgets) {
            this.thinkingBudgets = thinkingBudgets;
            return this;
        }

        // ========== Agent 专属字段设置方法 ==========

        /**
         * 设置目标 LLM 模型。
         * @param model 模型实例，如 Model.CLAUDE_SONNET_4_5
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        /**
         * 设置消息转换回调函数，用于将 Agent 层消息转换为 LLM 层消息。
         * @param convertToLlm 消息转换函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder convertToLlm(ConvertToLlmFunction convertToLlm) {
            this.convertToLlm = convertToLlm;
            return this;
        }

        /**
         * 设置上下文转换回调函数，用于在消息送入 LLM 前进行预处理。
         * @param transformContext 上下文转换函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder transformContext(TransformContextFunction transformContext) {
            this.transformContext = transformContext;
            return this;
        }

        /**
         * 设置 API Key 动态获取回调函数。
         * @param getApiKey API Key 获取函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder getApiKey(GetApiKeyFunction getApiKey) {
            this.getApiKey = getApiKey;
            return this;
        }

        /**
         * 设置引导消息获取回调函数。
         * @param getSteeringMessages 引导消息获取函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder getSteeringMessages(GetSteeringMessagesFunction getSteeringMessages) {
            this.getSteeringMessages = getSteeringMessages;
            return this;
        }

        /**
         * 设置后续消息获取回调函数。
         * @param getFollowUpMessages 后续消息获取函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder getFollowUpMessages(GetFollowUpMessagesFunction getFollowUpMessages) {
            this.getFollowUpMessages = getFollowUpMessages;
            return this;
        }

        /**
         * 设置工具执行模式。
         * @param toolExecution 工具执行模式（PARALLEL 并行 / SEQUENTIAL 串行）
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder toolExecution(ToolExecutionMode toolExecution) {
            this.toolExecution = toolExecution;
            return this;
        }

        /**
         * 设置工具执行前钩子，用于在工具调用前执行校验、拦截或审计逻辑。
         * @param beforeToolCall 工具执行前钩子函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder beforeToolCall(BeforeToolCallHook beforeToolCall) {
            this.beforeToolCall = beforeToolCall;
            return this;
        }

        /**
         * 设置工具执行后钩子，用于对工具执行结果进行后处理或覆盖。
         * @param afterToolCall 工具执行后钩子函数
         * @return 当前 Builder 实例，支持链式调用
         */
        public Builder afterToolCall(AfterToolCallHook afterToolCall) {
            this.afterToolCall = afterToolCall;
            return this;
        }

        /**
         * 构建 {@link AgentLoopConfig} 实例。
         * <p>调用此方法前，需确保已设置必要的字段（如 model、convertToLlm）。
         * @return 构建完成的 AgentLoopConfig 实例
         */
        public AgentLoopConfig build() {
            return new AgentLoopConfig(this);
        }

        /**
         * 从流式相关字段构建内部 {@link SimpleStreamOptions} 实例。
         * <p>此方法由 {@link #build()} 内部调用，将 Builder 中的流式参数
         * 组装成 SimpleStreamOptions 对象传递给 AgentLoopConfig。
         * @return 构建完成的 SimpleStreamOptions 实例
         */
        SimpleStreamOptions buildStreamOptions() {
            return SimpleStreamOptions.simpleBuilder()
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .apiKey(apiKey)
                    .cacheRetention(cacheRetention)
                    .sessionId(sessionId)
                    .headers(headers)
                    .transport(transport)
                    .maxRetryDelayMs(maxRetryDelayMs)
                    .metadata(metadata)
                    .onPayload(onPayload)
                    .signal(signal)
                    .reasoning(reasoning)
                    .thinkingBudgets(thinkingBudgets)
                    .build();
        }
    }
}

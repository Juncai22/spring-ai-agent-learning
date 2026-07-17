package com.pi.agent;

import com.pi.agent.config.AfterToolCallHook;
import com.pi.agent.config.BeforeToolCallHook;
import com.pi.agent.config.ConvertToLlmFunction;
import com.pi.agent.config.GetApiKeyFunction;
import com.pi.agent.config.StreamFn;
import com.pi.agent.config.TransformContextFunction;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentOptions;
import com.pi.agent.types.AgentState;
import com.pi.agent.types.AgentThinkingLevel;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.MessageAdapter;
import com.pi.agent.types.QueueMode;
import com.pi.agent.types.ToolExecutionMode;
import com.pi.ai.core.stream.PiAi;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Message;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.PayloadInterceptor;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ThinkingBudgets;
import com.pi.ai.core.types.Transport;
import com.pi.ai.core.types.UserMessage;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.event.EventStream;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.loop.AgentLoop;
import com.pi.agent.types.AgentContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Agent 框架的主类，负责管理 LLM（大语言模型）驱动的智能体交互生命周期。
 * 该类封装了状态管理、事件订阅、消息队列和生命周期控制等核心能力。
 *
 * <p><b>整体架构：</b>
 * <ul>
 *   <li><b>状态管理（AgentState）：</b> 维护智能体的运行时状态，包括系统提示词、模型配置、
 *       消息历史、工具列表、流式消息状态和错误信息等。</li>
 *   <li><b>消息队列：</b> 提供 steering（干预）和 follow-up（跟进）两个独立的消息队列，
 *       支持在智能体运行时从外部注入消息，实现交互式控制。</li>
 *   <li><b>事件系统：</b> 基于发布-订阅模式，支持消费者监听消息开始/更新/结束、工具执行
 *       开始/结束、轮次结束、智能体结束等事件。</li>
 *   <li><b>生命周期控制：</b> 提供 prompt（提示）、continueProcessing（继续处理）、
 *       abort（中止）、waitForIdle（等待空闲）、reset（重置）等完整生命周期方法。</li>
 *   <li><b>AgentLoop（主循环）：</b> 核心处理逻辑委托给 {@link AgentLoop} 类，
 *       后者负责 LLM 调用、工具执行的编排。</li>
 * </ul>
 *
 * <p><b>线程安全策略：</b>
 * <ul>
 *   <li>{@code isStreaming}（流式标志）—— {@link AgentState} 中的 volatile boolean，
 *       确保跨线程可见性</li>
 *   <li>{@code listeners}（事件监听器集合）—— 使用 {@link CopyOnWriteArraySet}，
 *       支持并发场景下的订阅/取消订阅，读操作无锁</li>
 *   <li>{@code steeringQueue}（干预消息队列）—— 使用 {@link ConcurrentLinkedQueue}，
 *       无锁并发队列，线程安全地入队</li>
 *   <li>{@code followUpQueue}（跟进消息队列）—— 使用 {@link ConcurrentLinkedQueue}，
 *       无锁并发队列，线程安全地入队</li>
 *   <li>{@code signal}（取消信号）—— volatile 引用，跨线程可见，用于取消正在运行的循环</li>
 *   <li>{@code runningPromise}（运行期承诺）—— volatile 引用，用于 waitForIdle 等待空闲</li>
 * </ul>
 *
 * <p><b>可配置项：</b>
 * <ul>
 *   <li><b>convertToLlm：</b> 消息转换函数，将 AgentMessage 列表转换为 LLM API 可识别的 Message 列表</li>
 *   <li><b>transformContext：</b> 上下文转换函数，在每次 LLM 调用前对 AgentContext 进行转换</li>
 *   <li><b>steeringMode / followUpMode：</b> 队列出队模式，支持 ONE_AT_A_TIME（一次一个）和 ALL（全部取出）</li>
 *   <li><b>streamFn：</b> LLM 流式调用函数，默认使用 {@link PiAi#streamSimple}</li>
 *   <li><b>sessionId：</b> 会话标识，用于提供商的缓存机制</li>
 *   <li><b>getApiKey：</b> 动态 API Key 获取函数，支持运行时动态切换密钥</li>
 *   <li><b>onPayload：</b> 请求载荷拦截器，用于调试和日志记录</li>
 *   <li><b>thinkingBudgets：</b> 思考预算配置，控制模型推理时的思考深度</li>
 *   <li><b>transport：</b> 传输协议，默认 SSE（Server-Sent Events）</li>
 *   <li><b>maxRetryDelayMs：</b> 最大重试延迟（毫秒）</li>
 *   <li><b>toolExecution：</b> 工具执行模式，支持 SEQUENTIAL（串行）和 PARALLEL（并行）</li>
 *   <li><b>beforeToolCall / afterToolCall：</b> 工具调用前后的钩子函数</li>
 * </ul>
 *
 * <p><b>需求覆盖：Requirements 23.1, 23.2, 23.3, 23.4, 23.5, 23.6, 23.7, 23.8, 23.9</b>
 *
 * @see AgentOptions
 * @see AgentState
 * @see AgentEvent
 * @see AgentLoop
 */
public class Agent {

    // ─────────────────────────────────────────────────────────────────────────────
    // 内部状态（Internal state）
    // ─────────────────────────────────────────────────────────────────────────────

    /** Agent 的运行时状态，包含系统提示词、模型、消息历史、工具列表等。 */
    private final AgentState state;

    /** 事件监听器集合，使用 CopyOnWriteArraySet 保证并发订阅/取消订阅的线程安全性。 */
    private final Set<Consumer<AgentEvent>> listeners;

    /** 干预消息队列（steering queue），线程安全，用于在运行时从外部注入"干预"消息。 */
    private final ConcurrentLinkedQueue<AgentMessage> steeringQueue;

    /** 跟进消息队列（follow-up queue），线程安全，用于在运行时从外部注入"跟进"消息。 */
    private final ConcurrentLinkedQueue<AgentMessage> followUpQueue;

    /** 当前取消信号，volatile 保证跨线程可见性，用于中止正在运行的 Agent 循环。 */
    private volatile CancellationSignal signal;

    /** 运行期承诺（CompletableFuture），volatile 保证可见性，用于 waitForIdle 等待空闲。 */
    private volatile CompletableFuture<Void> runningPromise;

    // ─────────────────────────────────────────────────────────────────────────────
    // 配置项（从 AgentOptions 中解析得到）
    // ─────────────────────────────────────────────────────────────────────────────

    /** 消息转换函数：将 AgentMessage 列表转换为 LLM API 可识别的 Message 列表。 */
    private ConvertToLlmFunction convertToLlm;

    /** 可选的上下文转换函数：在每次 LLM 调用前对 AgentContext 进行预处理/转换。 */
    private TransformContextFunction transformContext;

    /** 干预消息队列的出队模式（一次一个 / 全部取出）。 */
    private QueueMode steeringMode;

    /** 跟进消息队列的出队模式（一次一个 / 全部取出）。 */
    private QueueMode followUpMode;

    /** LLM 流式调用函数，默认使用 PiAi::streamSimple。 */
    private StreamFn streamFn;

    /** 会话标识符，用于提供商端缓存（如上下文缓存）。 */
    private String sessionId;

    /** 动态 API Key 解析函数，支持运行时动态获取/切换 API 密钥。 */
    private GetApiKeyFunction getApiKey;

    /** 请求载荷拦截器，用于调试/日志记录/监控目的。 */
    private PayloadInterceptor onPayload;

    /** 思考预算配置，控制模型推理时的思考深度和 token 分配。 */
    private ThinkingBudgets thinkingBudgets;

    /** 传输协议（如 SSE、WebSocket 等）。 */
    private Transport transport;

    /** 最大重试延迟（毫秒），用于 LLM 调用失败时的退避策略。 */
    private Integer maxRetryDelayMs;

    /** 工具执行模式：SEQUENTIAL（串行）或 PARALLEL（并行）。 */
    private ToolExecutionMode toolExecution;

    /** 工具调用前钩子，在每次工具调用前执行。 */
    private BeforeToolCallHook beforeToolCall;

    /** 工具调用后钩子，在每次工具调用完成后执行。 */
    private AfterToolCallHook afterToolCall;

    // ─────────────────────────────────────────────────────────────────────────────
    // 构造方法（Constructor）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 使用给定的配置选项创建一个 Agent 实例。
     *
     * <p>{@link AgentOptions} 中的所有字段均为可选。当未提供时使用以下默认值：
     * <ul>
     *   <li>{@code initialState}（初始状态）—— 使用默认状态（systemPrompt="", thinkingLevel=OFF,
     *       tools=[], messages=[], isStreaming=false）</li>
     *   <li>{@code convertToLlm}（消息转换函数）—— 使用默认转换器，仅保留 user/assistant/toolResult 角色消息</li>
     *   <li>{@code steeringMode}（干预模式）—— 默认 {@link QueueMode#ONE_AT_A_TIME}（一次处理一个）</li>
     *   <li>{@code followUpMode}（跟进模式）—— 默认 {@link QueueMode#ONE_AT_A_TIME}（一次处理一个）</li>
     *   <li>{@code streamFn}（流式函数）—— 默认 {@link PiAi#streamSimple}</li>
     *   <li>{@code transport}（传输协议）—— 默认 {@link Transport#SSE}</li>
     *   <li>{@code toolExecution}（工具执行模式）—— 默认 {@link ToolExecutionMode#PARALLEL}（并行）</li>
     * </ul>
     *
     * <p><b>构造流程：</b>
     * <ol>
     *   <li>初始化线程安全的集合（监听器、干预队列、跟进队列）</li>
     *   <li>从 options 中复制初始状态，或使用默认状态</li>
     *   <li>应用配置项到对应字段</li>
     * </ol>
     *
     * @param options 配置选项（可以为 null，此时全部使用默认值）
     */
    public Agent(AgentOptions options) {
        // 初始化线程安全的集合
        this.listeners = new CopyOnWriteArraySet<>();
        this.steeringQueue = new ConcurrentLinkedQueue<>();
        this.followUpQueue = new ConcurrentLinkedQueue<>();
        this.signal = null;
        this.runningPromise = null;

        // 从 options 中初始化状态，如果未提供则使用默认状态
        if (options != null && options.getInitialState() != null) {
            this.state = copyState(options.getInitialState());
        } else {
            this.state = createDefaultState();
        }

        // 应用配置项到对应字段（使用默认值兜底）
        applyOptions(options);
    }

    /**
     * 使用全部默认设置创建一个 Agent 实例。
     * 等价于 {@code new Agent(null)}，所有配置项均使用默认值。
     */
    public Agent() {
        this(null);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 初始化辅助方法（Initialization helpers）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 创建默认的 AgentState，所有字段初始化为安全的默认值。
     *
     * <p>默认值如下：
     * <ul>
     *   <li>systemPrompt = ""（空字符串）</li>
     *   <li>model = null（未设置模型）</li>
     *   <li>thinkingLevel = OFF（不启用思考模式）</li>
     *   <li>tools = 空列表</li>
     *   <li>messages = 空可变列表</li>
     *   <li>isStreaming = false（非流式状态）</li>
     *   <li>streamMessage = null（无流式消息）</li>
     *   <li>pendingToolCalls = 空集合</li>
     *   <li>error = null（无错误）</li>
     * </ul>
     *
     * @return 初始化后的默认 AgentState 实例
     */
    private AgentState createDefaultState() {
        return new AgentState();
    }

    /**
     * 深拷贝给定的 AgentState，确保可变集合（messages、tools）被复制为独立副本，
     * 避免外部修改影响 Agent 内部状态。
     *
     * <p>拷贝的字段包括：systemPrompt、model、thinkingLevel、tools（深拷贝）、
     * messages（深拷贝）、isStreaming、streamMessage、pendingToolCalls、error。
     *
     * @param source 源状态对象
     * @return 源状态的独立副本
     */
    private AgentState copyState(AgentState source) {
        AgentState copy = new AgentState();
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setModel(source.getModel());
        copy.setThinkingLevel(source.getThinkingLevel());
        // 深拷贝 tools 列表，避免共享引用
        copy.setTools(source.getTools() != null ? new ArrayList<>(source.getTools()) : new ArrayList<>());
        // 深拷贝 messages 列表，避免共享引用
        copy.setMessages(source.getMessages() != null ? new ArrayList<>(source.getMessages()) : new ArrayList<>());
        copy.setIsStreaming(source.isStreaming());
        copy.setStreamMessage(source.getStreamMessage());
        copy.setPendingToolCalls(source.getPendingToolCalls());
        copy.setError(source.getError());
        return copy;
    }

    /**
     * 从 AgentOptions 中提取配置项并应用到 Agent 实例字段。
     * 对于未设置的配置项，使用安全的默认值兜底。
     *
     * <p>处理流程：
     * <ol>
     *   <li>如果 options 为 null，所有字段使用默认值</li>
     *   <li>否则逐个检查 options 中的字段，非 null 则使用，null 则使用默认值</li>
     * </ol>
     *
     * @param options 配置选项（可以为 null）
     */
    private void applyOptions(AgentOptions options) {
        if (options == null) {
            // 所有配置项使用默认值
            this.convertToLlm = createDefaultConvertToLlm();
            this.transformContext = null;
            this.steeringMode = QueueMode.ONE_AT_A_TIME;
            this.followUpMode = QueueMode.ONE_AT_A_TIME;
            this.streamFn = createDefaultStreamFn();
            this.sessionId = null;
            this.getApiKey = null;
            this.onPayload = null;
            this.thinkingBudgets = null;
            this.transport = Transport.SSE;
            this.maxRetryDelayMs = null;
            this.toolExecution = ToolExecutionMode.PARALLEL;
            this.beforeToolCall = null;
            this.afterToolCall = null;
            return;
        }

        // 逐个检查 options 中的字段，非 null 则使用，null 则使用默认值
        this.convertToLlm = options.getConvertToLlm() != null
                ? options.getConvertToLlm()
                : createDefaultConvertToLlm();

        this.transformContext = options.getTransformContext();

        this.steeringMode = options.getSteeringMode() != null
                ? options.getSteeringMode()
                : QueueMode.ONE_AT_A_TIME;

        this.followUpMode = options.getFollowUpMode() != null
                ? options.getFollowUpMode()
                : QueueMode.ONE_AT_A_TIME;

        this.streamFn = options.getStreamFn() != null
                ? options.getStreamFn()
                : createDefaultStreamFn();

        this.sessionId = options.getSessionId();
        this.getApiKey = options.getGetApiKey();
        this.onPayload = options.getOnPayload();
        this.thinkingBudgets = options.getThinkingBudgets();

        this.transport = options.getTransport() != null
                ? options.getTransport()
                : Transport.SSE;

        this.maxRetryDelayMs = options.getMaxRetryDelayMs();

        this.toolExecution = options.getToolExecution() != null
                ? options.getToolExecution()
                : ToolExecutionMode.PARALLEL;

        this.beforeToolCall = options.getBeforeToolCall();
        this.afterToolCall = options.getAfterToolCall();
    }

    /**
     * 创建默认的 convertToLlm 函数，按角色过滤消息。
     *
     * <p>默认转换规则：
     * <ul>
     *   <li>仅保留 role 为 "user"、"assistant" 或 "toolResult" 的消息</li>
     *   <li>仅包含通过 {@link MessageAdapter} 包装的 LLM 消息</li>
     *   <li>自定义消息类型（如纯文本指令）被过滤掉</li>
     * </ul>
     *
     * @return 默认的消息转换函数
     */
    private ConvertToLlmFunction createDefaultConvertToLlm() {
        return messages -> {
            List<Message> result = new ArrayList<>();
            for (AgentMessage agentMsg : messages) {
                // 只处理 MessageAdapter 包装的 LLM 消息
                if (MessageAdapter.isLlmMessage(agentMsg)) {
                    String role = agentMsg.role();
                    // 只保留 user、assistant、toolResult 三种角色
                    if ("user".equals(role) || "assistant".equals(role) || "toolResult".equals(role)) {
                        result.add(MessageAdapter.unwrap(agentMsg));
                    }
                }
            }
            return result;
        };
    }

    /**
     * 创建默认的 streamFn，委托给 {@link PiAi#streamSimple} 进行 LLM 流式调用。
     * streamSimple 是 pi-ai-core 中最基础的流式 API 调用方法。
     *
     * @return 默认的流式调用函数
     */
    private StreamFn createDefaultStreamFn() {
        return PiAi::streamSimple;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 状态访问器（State accessors）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 获取当前 Agent 的运行时状态引用。
     * 返回的是内部状态对象的直接引用，调用方可以直接修改状态对象的内容。
     *
     * @return AgentState 实例，包含完整的运行时状态信息
     */
    public AgentState getState() {
        return state;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 状态修改方法（Req 26）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 设置系统提示词（system prompt）。
     * 系统提示词定义了 Agent 的"角色"和"行为准则"，在每次 LLM 调用时都会作为上下文的一部分发送。
     *
     * @param systemPrompt 新的系统提示词字符串
     */
    public void setSystemPrompt(String systemPrompt) {
        state.setSystemPrompt(systemPrompt);
    }

    /**
     * 设置 LLM 模型。
     * 模型决定了 Agent 使用的 AI 能力（如推理、生成等），切换模型会影响响应的质量和风格。
     *
     * @param model 新的模型配置
     */
    public void setModel(Model model) {
        state.setModel(model);
    }

    /**
     * 设置思考级别（thinking level）。
     * 思考级别控制模型在生成回复前的推理深度，高级别会花费更多 token 但可能获得更准确的推理结果。
     *
     * @param thinkingLevel 新的思考级别
     */
    public void setThinkingLevel(AgentThinkingLevel thinkingLevel) {
        state.setThinkingLevel(thinkingLevel);
    }

    /**
     * 设置工具列表。
     * 工具是 Agent 可以调用的外部能力（如搜索、计算、数据库查询等），
     * 设置后 LLM 可以在适当的时机选择调用这些工具。
     *
     * @param tools 新的工具列表
     */
    public void setTools(List<AgentTool> tools) {
        state.setTools(tools);
    }

    /**
     * 替换所有消息历史为给定列表的副本。
     * 此操作会完全替换内部的消息列表，而不是追加。
     * 适用于需要重置对话上下文或恢复历史会话的场景。
     *
     * @param messages 新的消息列表
     */
    public void replaceMessages(List<AgentMessage> messages) {
        state.setMessages(messages);
    }

    /**
     * 追加一条消息到消息历史中。
     * 消息会按追加顺序排列，LLM 在生成回复时会参考全部消息历史。
     *
     * @param message 要追加的消息
     */
    public void appendMessage(AgentMessage message) {
        state.getMessages().add(message);
    }

    /**
     * 清空所有消息历史。
     * 此操作会创建一个新的空消息列表，旧的引用将被丢弃。
     * 注意：此操作不会影响队列中的消息。
     */
    public void clearMessages() {
        state.setMessages(new ArrayList<>());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 配置项访问器与修改器（Configuration accessors and mutators）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 获取会话 ID。
     * 会话 ID 用于提供商端的缓存机制（如上下文缓存），支持跨请求的会话追踪。
     *
     * @return 会话 ID 字符串，可能为 null
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * 设置会话 ID。
     * 设置后，后续的 LLM 调用将使用此会话 ID 进行提供商端的缓存管理。
     *
     * @param sessionId 会话 ID 字符串
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 获取传输协议。
     * 传输协议决定了 Agent 与 LLM 服务端之间的通信方式。
     *
     * @return 当前传输协议，默认为 SSE
     */
    public Transport getTransport() {
        return transport;
    }

    /**
     * 设置传输协议。
     * 如果传入 null，则使用默认值 {@link Transport#SSE}。
     *
     * @param transport 传输协议，null 会被重置为默认值
     */
    public void setTransport(Transport transport) {
        this.transport = transport != null ? transport : Transport.SSE;
    }

    /**
     * 获取最大重试延迟（毫秒）。
     * 当 LLM 调用失败时，Agent 会使用退避策略重试，此值限制最大退避时间。
     *
     * @return 最大重试延迟毫秒数，可能为 null（表示使用系统默认值）
     */
    public Integer getMaxRetryDelayMs() {
        return maxRetryDelayMs;
    }

    /**
     * 设置最大重试延迟（毫秒）。
     *
     * @param maxRetryDelayMs 最大重试延迟毫秒数
     */
    public void setMaxRetryDelayMs(Integer maxRetryDelayMs) {
        this.maxRetryDelayMs = maxRetryDelayMs;
    }

    /**
     * 获取思考预算配置。
     * 思考预算控制模型在推理时的 token 分配策略，影响推理深度和响应质量。
     *
     * @return 思考预算配置，可能为 null
     */
    public ThinkingBudgets getThinkingBudgets() {
        return thinkingBudgets;
    }

    /**
     * 设置思考预算配置。
     *
     * @param thinkingBudgets 思考预算配置
     */
    public void setThinkingBudgets(ThinkingBudgets thinkingBudgets) {
        this.thinkingBudgets = thinkingBudgets;
    }

    /**
     * 获取工具执行模式。
     * 工具执行模式决定当 LLM 请求调用多个工具时，这些工具是按顺序执行还是并行执行。
     *
     * @return 当前工具执行模式，默认为 PARALLEL（并行）
     */
    public ToolExecutionMode getToolExecution() {
        return toolExecution;
    }

    /**
     * 设置工具执行模式。
     * 如果传入 null，则使用默认值 {@link ToolExecutionMode#PARALLEL}。
     *
     * @param toolExecution 工具执行模式，null 会被重置为默认值
     */
    public void setToolExecution(ToolExecutionMode toolExecution) {
        this.toolExecution = toolExecution != null ? toolExecution : ToolExecutionMode.PARALLEL;
    }

    /**
     * 设置工具调用前钩子函数。
     * 该钩子会在每次工具调用前执行，可用于参数校验、日志记录、权限检查等场景。
     *
     * @param beforeToolCall 工具调用前钩子函数
     */
    public void setBeforeToolCall(BeforeToolCallHook beforeToolCall) {
        this.beforeToolCall = beforeToolCall;
    }

    /**
     * 设置工具调用后钩子函数。
     * 该钩子会在每次工具调用完成后执行，可用于结果处理、日志记录、性能统计等场景。
     *
     * @param afterToolCall 工具调用后钩子函数
     */
    public void setAfterToolCall(AfterToolCallHook afterToolCall) {
        this.afterToolCall = afterToolCall;
    }

    /**
     * 获取干预消息队列的出队模式。
     * 干预队列用于在 Agent 运行时从外部注入"干预"消息，影响 Agent 的行为。
     *
     * @return 当前干预队列出队模式
     */
    public QueueMode getSteeringMode() {
        return steeringMode;
    }

    /**
     * 设置干预消息队列的出队模式。
     * 如果传入 null，则使用默认值 {@link QueueMode#ONE_AT_A_TIME}。
     *
     * @param steeringMode 干预队列出队模式，null 会被重置为默认值
     */
    public void setSteeringMode(QueueMode steeringMode) {
        this.steeringMode = steeringMode != null ? steeringMode : QueueMode.ONE_AT_A_TIME;
    }

    /**
     * 获取跟进消息队列的出队模式。
     * 跟进队列用于在 Agent 运行时从外部注入"跟进"消息，驱动 Agent 继续对话。
     *
     * @return 当前跟进队列出队模式
     */
    public QueueMode getFollowUpMode() {
        return followUpMode;
    }

    /**
     * 设置跟进消息队列的出队模式。
     * 如果传入 null，则使用默认值 {@link QueueMode#ONE_AT_A_TIME}。
     *
     * @param followUpMode 跟进队列出队模式，null 会被重置为默认值
     */
    public void setFollowUpMode(QueueMode followUpMode) {
        this.followUpMode = followUpMode != null ? followUpMode : QueueMode.ONE_AT_A_TIME;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 内部配置访问器（供 _runLoop 和 AgentLoop 使用）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 获取消息转换函数。
     * 包级私有方法，供 {@link AgentLoop} 在构建 AgentLoopConfig 时使用。
     *
     * @return 消息转换函数
     */
    ConvertToLlmFunction getConvertToLlm() {
        return convertToLlm;
    }

    /**
     * 获取上下文转换函数。
     * 包级私有方法，用于在每次 LLM 调用前对 AgentContext 进行预处理。
     *
     * @return 上下文转换函数，可能为 null
     */
    TransformContextFunction getTransformContext() {
        return transformContext;
    }

    /**
     * 获取流式调用函数。
     * 包级私有方法，用于执行 LLM 流式调用。
     *
     * @return 流式调用函数
     */
    StreamFn getStreamFn() {
        return streamFn;
    }

    /**
     * 获取动态 API Key 获取函数。
     * 包级私有方法，用于运行时动态获取/切换 API 密钥。
     *
     * @return API Key 获取函数，可能为 null
     */
    GetApiKeyFunction getGetApiKey() {
        return getApiKey;
    }

    /**
     * 获取请求载荷拦截器。
     * 包级私有方法，用于拦截和记录 LLM 请求载荷。
     *
     * @return 载荷拦截器，可能为 null
     */
    PayloadInterceptor getOnPayload() {
        return onPayload;
    }

    /**
     * 获取工具调用前钩子函数。
     * 包级私有方法，用于在工具调用前执行预处理逻辑。
     *
     * @return 工具调用前钩子，可能为 null
     */
    BeforeToolCallHook getBeforeToolCall() {
        return beforeToolCall;
    }

    /**
     * 获取工具调用后钩子函数。
     * 包级私有方法，用于在工具调用后执行后处理逻辑。
     *
     * @return 工具调用后钩子，可能为 null
     */
    AfterToolCallHook getAfterToolCall() {
        return afterToolCall;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 事件订阅（Req 27）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 订阅 Agent 事件。
     * 使用 {@link CopyOnWriteArraySet} 保证线程安全，支持在事件处理过程中动态添加/移除监听器。
     *
     * <p>支持的事件类型：{@link AgentEvent.MessageStart}、{@link AgentEvent.MessageUpdate}、
     * {@link AgentEvent.MessageEnd}、{@link AgentEvent.ToolExecutionStart}、
     * {@link AgentEvent.ToolExecutionEnd}、{@link AgentEvent.TurnEnd}、{@link AgentEvent.AgentEnd}。
     *
     * @param listener 事件监听器（Consumer 函数式接口）
     * @return 一个 Runnable，调用后会取消订阅此监听器
     */
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 向所有已订阅的监听器发送事件。
     * 包级私有方法，由 {@link AgentLoop} 和 {@link #_processLoopEvent} 调用。
     * 遍历监听器集合，逐个调用 accept 方法，因此监听器中的异常会影响所有后续监听器。
     *
     * @param event 要发送的事件对象
     */
    void emit(AgentEvent event) {
        for (Consumer<AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 队列操作（Req 28, 29）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 向干预消息队列（steering queue）中添加一条消息。
     * 干预消息用于在 Agent 正在处理时从外部注入"干预"指令，影响 Agent 的当前行为。
     * 例如：在 Agent 思考过程中注入新的指令来纠正其方向。
     * 使用 ConcurrentLinkedQueue，线程安全，可在任意线程调用。
     *
     * @param message 要干预的消息
     */
    public void steer(AgentMessage message) {
        steeringQueue.offer(message);
    }

    /**
     * 向跟进消息队列（follow-up queue）中添加一条消息。
     * 跟进消息用于在 Agent 完成当前处理后，注入后续的"跟进"指令。
     * 与干预消息不同，跟进消息会在当前轮次结束后处理，而不是立即处理。
     * 使用 ConcurrentLinkedQueue，线程安全，可在任意线程调用。
     *
     * @param message 要跟进的消息
     */
    public void followUp(AgentMessage message) {
        followUpQueue.offer(message);
    }

    /**
     * 清空干预消息队列。
     * 丢弃所有未处理的干预消息。
     */
    public void clearSteeringQueue() {
        steeringQueue.clear();
    }

    /**
     * 清空跟进消息队列。
     * 丢弃所有未处理的跟进消息。
     */
    public void clearFollowUpQueue() {
        followUpQueue.clear();
    }

    /**
     * 同时清空干预消息队列和跟进消息队列。
     * 丢弃所有未处理的消息。
     */
    public void clearAllQueues() {
        steeringQueue.clear();
        followUpQueue.clear();
    }

    /**
     * 检查是否有未处理的消息在任一队列中。
     * 用于在调用 continueProcessing 前判断是否需要继续处理。
     *
     * @return 如果干预队列或跟进队列非空，返回 true
     */
    public boolean hasQueuedMessages() {
        return !steeringQueue.isEmpty() || !followUpQueue.isEmpty();
    }

    /**
     * 根据出队模式从干预队列中取出消息。
     *
     * <p>出队策略：
     * <ul>
     *   <li>ONE_AT_A_TIME 模式：只取出队列中的第一条消息</li>
     *   <li>ALL 模式：取出队列中的所有消息（清空队列）</li>
     * </ul>
     *
     * <p>包级私有方法，供 {@link AgentLoop} 在轮询队列时使用。
     *
     * @return 取出的消息列表（可能为空列表）
     */
    List<AgentMessage> dequeueSteeringMessages() {
        // ONE_AT_A_TIME 模式：取第一条
        if (steeringMode == QueueMode.ONE_AT_A_TIME) {
            AgentMessage first = steeringQueue.poll();
            return first != null ? List.of(first) : List.of();
        }
        // ALL 模式：清空并取出全部
        List<AgentMessage> all = new ArrayList<>();
        AgentMessage msg;
        while ((msg = steeringQueue.poll()) != null) {
            all.add(msg);
        }
        return all;
    }

    /**
     * 根据出队模式从跟进队列中取出消息。
     *
     * <p>出队策略：
     * <ul>
     *   <li>ONE_AT_A_TIME 模式：只取出队列中的第一条消息</li>
     *   <li>ALL 模式：取出队列中的所有消息（清空队列）</li>
     * </ul>
     *
     * <p>包级私有方法，供 {@link AgentLoop} 在轮询队列时使用。
     *
     * @return 取出的消息列表（可能为空列表）
     */
    List<AgentMessage> dequeueFollowUpMessages() {
        // ONE_AT_A_TIME 模式：取第一条
        if (followUpMode == QueueMode.ONE_AT_A_TIME) {
            AgentMessage first = followUpQueue.poll();
            return first != null ? List.of(first) : List.of();
        }
        // ALL 模式：清空并取出全部
        List<AgentMessage> all = new ArrayList<>();
        AgentMessage msg;
        while ((msg = followUpQueue.poll()) != null) {
            all.add(msg);
        }
        return all;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 生命周期控制（Req 30）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 中止当前正在运行的 Agent 循环（如果正在运行）。
     * 通过调用 CancellationSignal 的 cancel() 方法来通知运行中的循环停止。
     *
     * <p>线程安全：可以从任意线程调用。cancel() 是幂等的，多次调用不会产生副作用。
     * 循环停止后，_runLoop 的 finally 块会执行状态清理（isStreaming=false 等）。
     */
    public void abort() {
        CancellationSignal currentSignal = this.signal;
        if (currentSignal != null) {
            currentSignal.cancel();
        }
    }

    /**
     * 返回一个 CompletableFuture，当 Agent 变为空闲状态时完成。
     * 用于等待正在运行的 Agent 循环结束。
     *
     * <p>如果当前没有运行中的循环，返回一个已完成（completed）的 future。
     * 如果有正在运行的循环，返回该循环的 runningPromise，在循环结束时完成。
     *
     * @return 一个 CompletableFuture，无返回值（Void），Agent 空闲时完成
     */
    public CompletableFuture<Void> waitForIdle() {
        CompletableFuture<Void> current = this.runningPromise;
        if (current != null) {
            return current;
        }
        // 没有运行中的循环，直接返回已完成状态
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 重置 Agent 到初始状态。
     * 执行以下清理操作：
     * <ul>
     *   <li>清空消息历史（setMessages(new ArrayList<>())）</li>
     *   <li>设置 isStreaming = false</li>
     *   <li>清空流式消息（setStreamMessage(null)）</li>
     *   <li>清空待处理的工具调用（clearPendingToolCalls()）</li>
     *   <li>清空错误信息（setError(null)）</li>
     *   <li>清空所有队列（clearAllQueues()）</li>
     * </ul>
     *
     * <p>注意：此方法不会重置系统提示词、模型、工具列表等配置项。
     * 如果要完全重置，需要重新创建 Agent 实例或手动重置这些配置。
     */
    public void reset() {
        state.setMessages(new ArrayList<>());
        state.setIsStreaming(false);
        state.setStreamMessage(null);
        state.clearPendingToolCalls();
        state.setError(null);
        clearAllQueues();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 内部信号量与承诺访问器（供 _runLoop 使用）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 获取当前的取消信号量。
     * 包级私有方法，供 {@link AgentLoop} 检查是否被取消。
     *
     * @return 当前取消信号量，如果没有运行中的循环则为 null
     */
    CancellationSignal getSignal() {
        return signal;
    }

    /**
     * 设置当前的取消信号量。
     * 包级私有方法，在 _runLoop 开始时设置，在 finally 块中置为 null。
     *
     * @param signal 新的取消信号量
     */
    void setSignal(CancellationSignal signal) {
        this.signal = signal;
    }

    /**
     * 获取当前的运行期承诺（running promise）。
     * 包级私有方法，用于检查或等待当前循环完成。
     *
     * @return 当前运行期承诺，如果没有运行中的循环则为 null
     */
    CompletableFuture<Void> getRunningPromise() {
        return runningPromise;
    }

    /**
     * 设置当前的运行期承诺。
     * 包级私有方法，在 _runLoop 开始时创建并设置，在 finally 块中完成。
     *
     * @param runningPromise 新的运行期承诺
     */
    void setRunningPromise(CompletableFuture<Void> runningPromise) {
        this.runningPromise = runningPromise;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Prompt（提示）和 Continue（继续）方法（Req 24, 25）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 向 Agent 发送一条纯文本提示。
     * 文本会被包装为 {@link UserMessage}（内含 {@link TextContent}），
     * 然后通过 {@link MessageAdapter} 适配为 AgentMessage 后发送。
     *
     * <p>这是最常用的入口方法，适用于大多数场景。
     * 内部会调用 {@link #prompt(AgentMessage)}，最终路由到 {@link #prompt(List)}。
     *
     * @param text 要发送的提示文本
     * @return 一个 CompletableFuture，在 Agent 循环完成时完成
     * @throws IllegalStateException 如果 Agent 正在流式处理中，或未配置模型
     *
     * <p><b>需求覆盖：Requirements 24.1, 24.2, 24.4, 24.5, 24.6</b>
     */
    public CompletableFuture<Void> prompt(String text) {
        // 创建 UserMessage 并包装为 AgentMessage
        UserMessage userMessage = new UserMessage(text, System.currentTimeMillis());
        AgentMessage agentMessage = MessageAdapter.wrap(userMessage);
        return prompt(agentMessage);
    }

    /**
     * 向 Agent 发送一条消息提示。
     * 允许发送预构建的 AgentMessage（如系统消息、工具结果等）。
     *
     * @param message 要发送的消息
     * @return 一个 CompletableFuture，在 Agent 循环完成时完成
     * @throws IllegalStateException 如果 Agent 正在流式处理中，或未配置模型
     *
     * <p><b>需求覆盖：Requirements 24.1, 24.4, 24.5, 24.6</b>
     */
    public CompletableFuture<Void> prompt(AgentMessage message) {
        return prompt(List.of(message));
    }

    /**
     * 向 Agent 发送一组消息提示。
     * 这是 prompt 方法族的最终实现，所有 prompt 重载最终都会路由到此方法。
     *
     * <p>处理流程：
     * <ol>
     *   <li>检查是否正在流式处理（Req 24.4）—— 如果是则抛出异常</li>
     *   <li>检查是否配置了模型（Req 24.5）—— 如果未配置则抛出异常</li>
     *   <li>调用 _runLoop 启动 Agent 循环（Req 24.6）—— continueMode=false</li>
     * </ol>
     *
     * <p>如果 Agent 正在运行中，应使用 {@link #steer} 或 {@link #followUp} 方法向队列中注入消息，
     * 而不是直接调用 prompt。
     *
     * @param messages 要发送的消息列表
     * @return 一个 CompletableFuture，在 Agent 循环完成时完成
     * @throws IllegalStateException 如果 Agent 正在流式处理中，或未配置模型
     *
     * <p><b>需求覆盖：Requirements 24.1, 24.4, 24.5, 24.6</b>
     */
    public CompletableFuture<Void> prompt(List<AgentMessage> messages) {
        // 检查是否正在流式处理中（Req 24.4）
        if (state.isStreaming()) {
            throw new IllegalStateException(
                    "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.");
        }

        // 检查是否配置了模型（Req 24.5）
        if (state.getModel() == null) {
            throw new IllegalStateException("No model configured");
        }

        // 启动 Agent 循环（Req 24.6），continueMode=false 表示新的一轮对话
        return _runLoop(messages, false);
    }

    /**
     * 从当前上下文继续处理 Agent。
     * 用于在 Agent 完成一轮处理后，根据当前消息历史继续对话。
     *
     * <p>处理流程：
     * <ol>
     *   <li>检查是否正在流式处理（Req 25.1）—— 如果是则抛出异常</li>
     *   <li>检查是否配置了模型</li>
     *   <li>检查消息历史是否存在（Req 25.2）—— 如果为空则抛出异常</li>
     *   <li>获取最后一条消息的角色</li>
     *   <li>如果最后一条是 assistant 消息：
     *     <ul>
     *       <li>优先检查是否有干预消息（Req 25.3）—— 有则使用干预消息启动新循环</li>
     *       <li>其次检查是否有跟进消息（Req 25.4）—— 有则使用跟进消息启动新循环</li>
     *       <li>都没有则抛出异常（Req 25.5），因为不能从 assistant 消息继续</li>
     *     </ul>
     *   </li>
     *   <li>如果最后一条不是 assistant 消息（如 user 或 toolResult），
     *       以 continueMode=true 启动循环（Req 25.6）</li>
     * </ol>
     *
     * @return 一个 CompletableFuture，在 Agent 循环完成时完成
     * @throws IllegalStateException 如果 Agent 正在流式处理、没有消息历史、
     *         或最后一条消息来自 assistant 且没有队列消息
     *
     * <p><b>需求覆盖：Requirements 25.1, 25.2, 25.3, 25.4, 25.5, 25.6</b>
     */
    public CompletableFuture<Void> continueProcessing() {
        // 检查是否正在流式处理中（Req 25.1）
        if (state.isStreaming()) {
            throw new IllegalStateException(
                    "Agent is already processing. Wait for completion before continuing.");
        }

        // 检查是否配置了模型
        if (state.getModel() == null) {
            throw new IllegalStateException("No model configured");
        }

        // 检查消息历史是否存在（Req 25.2）
        List<AgentMessage> messages = state.getMessages();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException("No messages to continue from");
        }

        // 获取最后一条消息的角色
        AgentMessage lastMessage = messages.get(messages.size() - 1);
        String lastRole = lastMessage.role();

        if ("assistant".equals(lastRole)) {
            // 最后一条是 assistant 消息：检查是否有队列消息（Req 25.3, 25.4）
            List<AgentMessage> steeringMessages = dequeueSteeringMessages();
            if (!steeringMessages.isEmpty()) {
                // 使用干预消息作为新提示启动循环，跳过首次干预轮询（Req 25.3）
                return _runLoop(steeringMessages, false);
            }

            // 检查是否有跟进消息（Req 25.4）
            List<AgentMessage> followUpMessages = dequeueFollowUpMessages();
            if (!followUpMessages.isEmpty()) {
                // 使用跟进消息作为新提示启动循环
                return _runLoop(followUpMessages, false);
            }

            // 没有队列消息，不能从 assistant 继续（Req 25.5）
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }

        // 最后一条不是 assistant 消息，以 continue 模式启动循环（Req 25.6）
        return _runLoop(List.of(), true);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 内部循环管理（Internal loop management, Req 31）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 运行 Agent 主循环的内部方法。
     * 这是整个 Agent 框架的核心方法，负责编排 LLM 调用和事件处理的全流程。
     *
     * <p><b>执行流程：</b>
     * <ol>
     *   <li>创建 {@link CancellationSignal} 并设置 isStreaming = true（Req 31.1）</li>
     *   <li>从当前状态构建 {@link AgentContext} 和 {@link AgentLoopConfig}（Req 31.2）</li>
     *   <li>根据 continueMode 选择调用 {@link AgentLoop#agentLoop} 或
     *       {@link AgentLoop#agentLoopContinue}，获取事件流</li>
     *   <li>遍历事件流，调用 {@link #_processLoopEvent} 处理每个事件</li>
     *   <li>正常完成时，将上下文中的消息同步回状态（Req 31.3）</li>
     *   <li>异常时，创建错误 AssistantMessage 并发送 agent_end 事件（Req 31.4, 41.4, 41.5）</li>
     *   <li>finally 块始终执行状态清理（Req 31.5）</li>
     * </ol>
     *
     * <p>整个循环在 {@link CompletableFuture#runAsync} 中异步执行，
     * 不会阻塞调用线程。调用方通过返回的 CompletableFuture 获取执行结果。
     *
     * <p><b>需求覆盖：Requirements 31.1, 31.2, 31.3, 31.4, 31.5, 41.4, 41.5</b>
     *
     * @param prompts 要作为提示发送的消息列表（continue 模式时为空列表）
     * @param continueMode true 表示从现有上下文继续，false 表示新的一轮提示
     * @return 一个 CompletableFuture，在循环完成时完成
     */
    CompletableFuture<Void> _runLoop(List<AgentMessage> prompts, boolean continueMode) {
        // 创建取消信号量（Req 31.1）
        CancellationSignal newSignal = new CancellationSignal();
        this.signal = newSignal;

        // 设置流式状态标志为 true（Req 31.1）
        state.setIsStreaming(true);

        // 清除之前的错误状态
        state.setError(null);

        // 创建运行期承诺，供 waitForIdle 等待
        CompletableFuture<Void> promise = new CompletableFuture<>();
        this.runningPromise = promise;

        // 在异步线程中执行循环
        CompletableFuture.runAsync(() -> {
            try {
                // 从当前状态构建 AgentContext（Req 31.2）
                // 注意：消息列表使用深拷贝，避免循环执行期间外部修改影响内部状态
                AgentContext context = AgentContext.builder()
                        .systemPrompt(state.getSystemPrompt())
                        .messages(new ArrayList<>(state.getMessages()))
                        .tools(state.getTools())
                        .build();

                // 从当前配置构建 AgentLoopConfig（Req 31.2）
                // 此配置包含了 LLM 调用的所有参数和钩子函数
                Model model = state.getModel();
                AgentLoopConfig config = AgentLoopConfig.builder()
                        .model(model)
                        .convertToLlm(convertToLlm)
                        .transformContext(transformContext)
                        .getApiKey(getApiKey)
                        .getSteeringMessages(this::dequeueSteeringMessagesAsync)
                        .getFollowUpMessages(this::dequeueFollowUpMessagesAsync)
                        .toolExecution(toolExecution)
                        .beforeToolCall(beforeToolCall)
                        .afterToolCall(afterToolCall)
                        .sessionId(sessionId)
                        .transport(transport)
                        .maxRetryDelayMs(maxRetryDelayMs)
                        .onPayload(onPayload)
                        .thinkingBudgets(thinkingBudgets)
                        .reasoning(state.getThinkingLevel() != null
                                ? state.getThinkingLevel().toPiAiThinkingLevel()
                                : null)
                        .build();

                // 根据 continueMode 选择对应的方法
                // agentLoop：新提示 -> 调用 LLM -> 处理工具 -> 循环
                // agentLoopContinue：从现有上下文继续，自动从最后一条消息的角色决定行为
                EventStream<AgentEvent, List<AgentMessage>> eventStream;
                if (continueMode) {
                    eventStream = AgentLoop.agentLoopContinue(context, config, newSignal, streamFn);
                } else {
                    eventStream = AgentLoop.agentLoop(prompts, context, config, newSignal, streamFn);
                }

                // 遍历事件流，对每个事件调用 _processLoopEvent 处理
                // 事件流是惰性求值的（lazy），每次迭代都会触发实际的 LLM 调用或工具执行
                for (AgentEvent event : eventStream) {
                    _processLoopEvent(event);
                }

                // 正常完成：将上下文中的消息同步回状态（Req 31.3）
                // 使用深拷贝避免上下文被后续操作意外修改
                state.setMessages(new ArrayList<>(context.getMessages()));

            } catch (Exception e) {
                // 异常处理：创建错误 AssistantMessage 并发送 agent_end 事件（Req 31.4, 41.4, 41.5）
                Model model = state.getModel();
                // 根据是否被取消来决定停止原因
                StopReason stopReason = (newSignal.isCancelled())
                        ? StopReason.ABORTED   // 被用户中止
                        : StopReason.ERROR;    // 发生异常错误

                // 构建错误消息，包含异常信息
                AssistantMessage errorMsg = AssistantMessage.builder()
                        .content(List.of(new TextContent("")))
                        .api(model != null ? model.api() : null)
                        .provider(model != null ? model.provider() : null)
                        .model(model != null ? model.id() : null)
                        .usage(createEmptyUsage())
                        .stopReason(stopReason)
                        .errorMessage(e.getMessage())  // 将异常信息存入错误消息
                        .timestamp(System.currentTimeMillis())
                        .build();

                // 将错误消息包装并追加到消息历史
                AgentMessage wrappedErrorMsg = MessageAdapter.wrap(errorMsg);
                appendMessage(wrappedErrorMsg);
                // 设置状态中的错误信息
                state.setError(e.getMessage());
                // 发送 agent_end 事件通知监听器
                emit(new AgentEvent.AgentEnd(List.of(wrappedErrorMsg)));

            } finally {
                // finally 块始终执行状态清理（Req 31.5）
                state.setIsStreaming(false);       // 重置流式状态
                state.setStreamMessage(null);       // 清空流式消息
                state.setPendingToolCalls(new CopyOnWriteArraySet<>());  // 清空待处理工具调用
                this.signal = null;                 // 清除取消信号引用

                // 完成运行期承诺，通知 waitForIdle 等待者
                promise.complete(null);
            }
        });

        return promise;
    }

    /**
     * 创建空的 Usage 实例，用于错误消息的场景。
     * 当循环因异常终止时，使用此方法创建一个全零的用量统计对象，
     * 表示没有任何 token 消耗。
     *
     * @return 全零的 Usage 实例
     */
    private Usage createEmptyUsage() {
        return new Usage(0, 0, 0, 0, 0, new Usage.Cost(0.0, 0.0, 0.0, 0.0, 0.0));
    }

    /**
     * 干预消息出队的异步包装方法。
     * 将同步的 {@link #dequeueSteeringMessages()} 包装为 CompletableFuture，
     * 以满足 {@link AgentLoopConfig} 中异步获取消息的接口签名。
     * 由于 ConcurrentLinkedQueue 的 poll 操作本身是轻量级的，直接返回已完成 future。
     *
     * @return 包含出队消息列表的 CompletableFuture
     */
    private CompletableFuture<List<AgentMessage>> dequeueSteeringMessagesAsync() {
        return CompletableFuture.completedFuture(dequeueSteeringMessages());
    }

    /**
     * 跟进消息出队的异步包装方法。
     * 将同步的 {@link #dequeueFollowUpMessages()} 包装为 CompletableFuture，
     * 以满足 {@link AgentLoopConfig} 中异步获取消息的接口签名。
     * 由于 ConcurrentLinkedQueue 的 poll 操作本身是轻量级的，直接返回已完成 future。
     *
     * @return 包含出队消息列表的 CompletableFuture
     */
    private CompletableFuture<List<AgentMessage>> dequeueFollowUpMessagesAsync() {
        return CompletableFuture.completedFuture(dequeueFollowUpMessages());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 内部事件处理（Internal event processing, Req 32）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 处理来自 Agent 循环的单个事件，更新内部状态并分发给所有监听器。
     *
     * <p><b>状态转换规则：</b>
     * <ul>
     *   <li>{@code message_start（消息开始）} → streamMessage = event.message（设置流式消息）</li>
     *   <li>{@code message_update（消息更新）} → streamMessage = event.message（更新流式消息）</li>
     *   <li>{@code message_end（消息结束）} → streamMessage = null,
     *       messages.add(event.message)（清除流式消息，追加到消息历史）</li>
     *   <li>{@code tool_execution_start（工具执行开始）} →
     *       pendingToolCalls.add(event.toolCallId)（记录待处理工具调用）</li>
     *   <li>{@code tool_execution_end（工具执行结束）} →
     *       pendingToolCalls.remove(event.toolCallId)（移除已完成的工具调用）</li>
     *   <li>{@code turn_end（轮次结束）} → 如果消息是 AssistantMessage 且包含 errorMessage，
     *       设置 error 状态</li>
     *   <li>{@code agent_end（Agent 结束）} → isStreaming = false, streamMessage = null</li>
     * </ul>
     *
     * <p>状态更新后，调用 {@link #emit(AgentEvent)} 将事件分发给所有订阅的监听器（Req 32.7）。
     * 注意：监听器的执行发生在状态更新之后，因此监听器可以获取到最新的状态。
     *
     * <p><b>需求覆盖：Requirements 32.1, 32.2, 32.3, 32.4, 32.5, 32.6, 32.7</b>
     *
     * @param event 要处理的事件对象
     */
    void _processLoopEvent(AgentEvent event) {
        // 根据事件类型更新状态（使用 Java 17 的 if-else instanceof 模式匹配）
        if (event instanceof AgentEvent.MessageStart messageStart) {
            // Req 32.1: message_start -> streamMessage = message（流式消息开始）
            state.setStreamMessage(messageStart.message());

        } else if (event instanceof AgentEvent.MessageUpdate messageUpdate) {
            // Req 32.2: message_update -> streamMessage = message（流式消息增量更新）
            state.setStreamMessage(messageUpdate.message());

        } else if (event instanceof AgentEvent.MessageEnd messageEnd) {
            // Req 32.3: message_end -> streamMessage = null, messages.add(message)
            // 流式消息结束，清除临时消息引用，将完整消息追加到历史记录
            state.setStreamMessage(null);
            state.getMessages().add(messageEnd.message());

        } else if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
            // Req 32.4: tool_execution_start -> pendingToolCalls.add(toolCallId)
            // 记录工具调用开始，用于追踪哪些工具调用仍在进行中
            state.addPendingToolCall(toolStart.toolCallId());

        } else if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
            // Req 32.5: tool_execution_end -> pendingToolCalls.remove(toolCallId)
            // 移除已完成的工具调用记录
            state.removePendingToolCall(toolEnd.toolCallId());

        } else if (event instanceof AgentEvent.TurnEnd turnEnd) {
            // 检查轮次结束事件中的消息是否为 AssistantMessage 且包含错误信息
            AgentMessage message = turnEnd.message();
            if (message instanceof MessageAdapter adapter
                    && adapter.message() instanceof AssistantMessage assistantMsg) {
                String errorMessage = assistantMsg.getErrorMessage();
                // 如果 LLM 返回了错误消息，将错误信息设置到状态中
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    state.setError(errorMessage);
                }
            }

        } else if (event instanceof AgentEvent.AgentEnd) {
            // Req 32.6: agent_end -> isStreaming = false, streamMessage = null
            // Agent 结束时重置流式状态
            state.setIsStreaming(false);
            state.setStreamMessage(null);
        }

        // Req 32.7: 状态更新后，将事件分发给所有监听器
        emit(event);
    }
}

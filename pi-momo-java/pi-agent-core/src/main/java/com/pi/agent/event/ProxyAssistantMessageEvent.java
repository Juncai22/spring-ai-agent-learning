package com.pi.agent.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.Usage;

/**
 * 代理助手消息事件 —— 对 {@link com.pi.ai.core.event.AssistantMessageEvent} 的带宽优化版本。
 *
 * <p>与 {@code AssistantMessageEvent} 的关键区别：
 * <ul>
 *   <li><b>Delta 事件不包含 {@code partial} 字段</b>（带宽优化，减少传输数据量）</li>
 *   <li><b>Start 事件不包含 {@code partial} 字段</b>（同上）</li>
 *   <li><b>Done/Error 事件直接携带 {@code reason} 和 {@code usage}</b>，而非携带完整消息对象</li>
 * </ul>
 *
 * <p>使用 Jackson 多态序列化，基于 {@code type} 字段作为鉴别器（discriminator）。
 * 序列化时根据 type 值与对应的子类型进行匹配。
 *
 * <p>设计目的：在代理（Proxy）场景下，减少事件数据的传输体积，提高网络传输效率。
 * 适用于需要通过中间层转发流式事件的场景，如 RPC 通信或代理服务。
 *
 * <p><b>验证需求：34.1, 34.2, 34.3, 34.4, 34.5, 34.6, 34.7, 34.8</b>
 *
 * @see ProxyAssistantMessageEvent.Start 流开始事件
 * @see ProxyAssistantMessageEvent.TextStart 文本内容块开始
 * @see ProxyAssistantMessageEvent.TextDelta 文本增量更新
 * @see ProxyAssistantMessageEvent.TextEnd 文本内容块结束
 * @see ProxyAssistantMessageEvent.ThinkingStart 思考内容块开始
 * @see ProxyAssistantMessageEvent.ThinkingDelta 思考增量更新
 * @see ProxyAssistantMessageEvent.ThinkingEnd 思考内容块结束
 * @see ProxyAssistantMessageEvent.ToolCallStart 工具调用开始
 * @see ProxyAssistantMessageEvent.ToolCallDelta 工具调用增量更新
 * @see ProxyAssistantMessageEvent.ToolCallEnd 工具调用结束
 * @see ProxyAssistantMessageEvent.Done 正常完成事件
 * @see ProxyAssistantMessageEvent.Error 错误终止事件
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.Start.class, name = "start"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.TextStart.class, name = "text_start"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.TextDelta.class, name = "text_delta"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.TextEnd.class, name = "text_end"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ThinkingStart.class, name = "thinking_start"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ThinkingDelta.class, name = "thinking_delta"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ThinkingEnd.class, name = "thinking_end"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ToolCallStart.class, name = "toolcall_start"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ToolCallDelta.class, name = "toolcall_delta"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.ToolCallEnd.class, name = "toolcall_end"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.Done.class, name = "done"),
    @JsonSubTypes.Type(value = ProxyAssistantMessageEvent.Error.class, name = "error")
})
public sealed interface ProxyAssistantMessageEvent
        permits ProxyAssistantMessageEvent.Start,
                ProxyAssistantMessageEvent.TextStart,
                ProxyAssistantMessageEvent.TextDelta,
                ProxyAssistantMessageEvent.TextEnd,
                ProxyAssistantMessageEvent.ThinkingStart,
                ProxyAssistantMessageEvent.ThinkingDelta,
                ProxyAssistantMessageEvent.ThinkingEnd,
                ProxyAssistantMessageEvent.ToolCallStart,
                ProxyAssistantMessageEvent.ToolCallDelta,
                ProxyAssistantMessageEvent.ToolCallEnd,
                ProxyAssistantMessageEvent.Done,
                ProxyAssistantMessageEvent.Error {

    /**
     * 获取事件类型的鉴别器字符串。
     * <p>该值将作为 JSON 序列化中的 {@code type} 字段，用于区分不同的事件子类型。
     * 例如 "start"、"text_delta"、"done" 等。
     *
     * @return 事件类型字符串
     */
    String type();

    // ── 流生命周期 ─────────────────────────────────────────────────

    /**
     * 流开始事件。
     * <p>当代理助手消息流开始传输时触发，表示流式响应的起始点。
     * 不包含 partial 字段（需求 34.3），以最小化初始传输数据量。
     */
    record Start() implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "start"; }
    }

    // ── 文本内容事件 ──────────────────────────────────────────────

    /**
     * 文本内容块开始事件。
     * <p>当一个新的文本内容块开始输出时触发，标记文本块的起始位置。
     * 一个流式响应可能包含多个内容块，通过 contentIndex 进行区分（需求 34.6）。
     *
     * @param contentIndex 内容块的索引序号，从 0 开始递增
     */
    record TextStart(int contentIndex) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "text_start"; }
    }

    /**
     * 文本增量更新事件。
     * <p>在文本内容块流式输出过程中，每次收到新的文本片段时触发。
     * 不包含 partial 字段（需求 34.2），只传输增量数据以减少带宽消耗。
     *
     * @param contentIndex 内容块的索引序号
     * @param delta        本次增量追加的文本片段
     */
    record TextDelta(int contentIndex, String delta) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "text_delta"; }
    }

    /**
     * 文本内容块结束事件。
     * <p>当文本内容块的输出完成时触发，标记该文本块的终止位置。
     * 可选的 contentSignature 用于内容完整性校验（需求 34.6, 34.7）。
     * 当 contentSignature 为 null 时，JSON 序列化将忽略该字段。
     *
     * @param contentIndex      内容块的索引序号
     * @param contentSignature  内容签名（可选），用于校验文本内容的完整性
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TextEnd(int contentIndex, String contentSignature) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "text_end"; }
    }

    // ── 思考内容事件 ──────────────────────────────────────────

    /**
     * 思考内容块开始事件。
     * <p>当模型开始输出思考过程（thinking/reasoning）时触发，标记思考块的起始位置（需求 34.6）。
     * 思考内容通常用于展示模型的推理过程，与最终文本输出分开处理。
     *
     * @param contentIndex 内容块的索引序号，从 0 开始递增
     */
    record ThinkingStart(int contentIndex) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "thinking_start"; }
    }

    /**
     * 思考增量更新事件。
     * <p>在思考内容块流式输出过程中，每次收到新的思考片段时触发。
     * 不包含 partial 字段（需求 34.2），只传输增量数据以减少带宽消耗。
     *
     * @param contentIndex 内容块的索引序号
     * @param delta        本次增量追加的思考内容片段
     */
    record ThinkingDelta(int contentIndex, String delta) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "thinking_delta"; }
    }

    /**
     * 思考内容块结束事件。
     * <p>当思考内容块的输出完成时触发，标记该思考块的终止位置。
     * 可选的 contentSignature 用于内容完整性校验（需求 34.6, 34.7）。
     * 当 contentSignature 为 null 时，JSON 序列化将忽略该字段。
     *
     * @param contentIndex      内容块的索引序号
     * @param contentSignature  内容签名（可选），用于校验思考内容的完整性
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ThinkingEnd(int contentIndex, String contentSignature) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "thinking_end"; }
    }

    // ── 工具调用事件 ─────────────────────────────────────────────────

    /**
     * 工具调用开始事件。
     * <p>当模型决定调用某个工具并开始输出工具调用参数时触发。
     * 包含工具调用的唯一标识符和工具名称（需求 34.8）。
     *
     * @param contentIndex 内容块的索引序号
     * @param id           工具调用的唯一标识符，用于后续关联工具调用的结果
     * @param toolName     被调用的工具名称
     */
    record ToolCallStart(int contentIndex, String id, String toolName) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "toolcall_start"; }
    }

    /**
     * 工具调用增量更新事件。
     * <p>在工具调用参数流式输出过程中，每次收到新的参数片段时触发。
     * 不包含 partial 字段（需求 34.2），只传输增量数据以减少带宽消耗。
     * 通常工具调用参数以 JSON 格式增量输出，各 delta 片段拼接后形成完整参数。
     *
     * @param contentIndex 内容块的索引序号
     * @param delta        本次增量追加的工具调用参数字符串片段
     */
    record ToolCallDelta(int contentIndex, String delta) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "toolcall_delta"; }
    }

    /**
     * 工具调用结束事件。
     * <p>当工具调用参数的输出完成时触发，标记该工具调用块的终止位置。
     * 此时可拼接所有 delta 片段得到完整的工具调用参数。
     *
     * @param contentIndex 内容块的索引序号
     */
    record ToolCallEnd(int contentIndex) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "toolcall_end"; }
    }

    // ── 终止事件 ──────────────────────────────────────────────────

    /**
     * 正常完成事件。
     * <p>当流式消息正常结束（而非因错误中断）时触发（需求 34.4）。
     * 直接携带停止原因和 Token 用量统计信息，而非携带完整消息对象，
     * 以减少最终事件的数据传输量。
     *
     * @param reason 停止原因，例如 stop（正常停止）、length（长度限制）、toolUse（工具调用）
     * @param usage  Token 用量统计信息，包含输入和输出的 Token 数量
     */
    record Done(StopReason reason, Usage usage) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "done"; }
    }

    /**
     * 错误终止事件。
     * <p>当流式消息因错误而终止时触发（需求 34.5）。
     * 用于通知客户端发生了异常，并携带错误信息以便排查问题。
     * 当 errorMessage 为 null 时，JSON 序列化将忽略该字段。
     *
     * @param reason       停止原因，例如 aborted（中止）或 error（错误）
     * @param errorMessage 错误描述信息（可选），提供详细的错误说明
     * @param usage        Token 用量统计信息，包含已消耗的 Token 数量
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Error(StopReason reason, String errorMessage, Usage usage) implements ProxyAssistantMessageEvent {
        @Override
        public String type() { return "error"; }
    }
}
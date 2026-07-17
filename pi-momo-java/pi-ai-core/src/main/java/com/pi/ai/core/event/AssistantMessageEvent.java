package com.pi.ai.core.event;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.ToolCall;

/**
 * LLM 流式响应事件的统一 sealed interface（密封接口），共包含 12 种事件类型。
 *
 * <p>该接口使用 Java 17 的 sealed 特性，明确限定所有允许的实现/记录类型，
 * 保证事件类型的完备性和可枚举性，编译器可以检查 exhaustiveness（穷尽性），
 * 在 switch 表达式中无需 default 分支即可覆盖所有情况。
 *
 * <p>事件按生命周期分为三类：
 * <ul>
 *   <li><b>流开始事件：</b>{@link Start} — 标记响应的开始，携带初始的 partial 消息</li>
 *   <li><b>内容事件（Content Block）：</b>LLM 响应包含按顺序排列的内容块（Content Block），
 *       每个内容块有三种事件：开始（Start）、增量（Delta）、结束（End），
 *       涵盖文本（Text）、思考内容（Thinking）和工具调用（ToolCall）三种类型</li>
 *   <li><b>终止事件：</b>{@link Done}（正常完成，携带停止原因和最终消息）、
 *       {@link Error}（错误终止，携带错误信息和原因）</li>
 * </ul>
 *
 * <p><b>内容块事件流顺序：</b>
 * 每个内容块（Content Block）在流中会依次产生 Start → Delta（0 到多次）→ End 事件。
 * 多个内容块之间按 block index 顺序排列，例如：
 * <pre>{@code
 * TextStart(idx=0) → TextDelta("思考中...") → TextEnd(idx=0, "思考中...") →
 * ToolCallStart(idx=1) → ToolCallDelta("{\"name\":\"...") → ToolCallEnd(idx=1, toolCall)
 * }</pre>
 */
public sealed interface AssistantMessageEvent
        permits AssistantMessageEvent.Start,                // 流开始事件
                AssistantMessageEvent.TextStart,            // 文本内容块开始
                AssistantMessageEvent.TextDelta,            // 文本增量
                AssistantMessageEvent.TextEnd,              // 文本内容块结束
                AssistantMessageEvent.ThinkingStart,        // 思考内容块开始
                AssistantMessageEvent.ThinkingDelta,        // 思考增量
                AssistantMessageEvent.ThinkingEnd,          // 思考内容块结束
                AssistantMessageEvent.ToolCallStart,        // 工具调用内容块开始
                AssistantMessageEvent.ToolCallDelta,        // 工具调用参数增量
                AssistantMessageEvent.ToolCallEnd,          // 工具调用内容块结束
                AssistantMessageEvent.Done,                 // 正常完成
                AssistantMessageEvent.Error {               // 错误终止

    /**
     * 获取事件类型标识符，用于运行时判断事件类型。
     * 返回值为字符串常量，如 "start"、"text_delta"、"done" 等。
     *
     * @return 事件类型字符串标识
     */
    String type();

    /**
     * 流开始事件，标记 LLM 响应的开始。
     * 在收到第一个事件时触发，携带初始的 partial AssistantMessage 对象，
     * 此时消息内容可能为空，后续通过增量事件逐步填充。
     */
    record Start(AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "start"; }  // 返回事件类型标识 "start"
    }

    /**
     * 文本内容块开始事件，标记一个新的文本内容块的开始。
     * 当 LLM 开始生成文本内容时触发，contentIndex 表示当前内容块在整个响应中的索引位置。
     * 如果 LLM 响应包含多个内容块（如文本 + 工具调用），每个内容块都有独立的索引。
     *
     * @param contentIndex 内容块索引，从 0 开始递增，同一内容块的所有事件共享同一个索引
     * @param partial      当前的 partial AssistantMessage，包含此内容块之前的所有内容
     */
    record TextStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "text_start"; }  // 返回事件类型标识 "text_start"
    }

    /**
     * 文本增量事件，携带新增的文本片段。
     * 在 TextStart 之后可能触发多次，每次携带自上次事件以来新增的文本 delta。
     * 消费者可以通过累加 delta 逐步构建完整的文本内容，实现流式打字机效果。
     *
     * @param contentIndex 内容块索引
     * @param delta        本次新增的文本片段，与前一次 delta 拼接即为完整文本
     * @param partial      当前的 partial AssistantMessage，包含此内容块之前的所有内容
     */
    record TextDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "text_delta"; }  // 返回事件类型标识 "text_delta"
    }

    /**
     * 文本内容块结束事件，携带该内容块的完整文本内容。
     * 在 TextDelta 全部发送完毕后触发，content 为所有 delta 拼接后的完整文本。
     */
    record TextEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "text_end"; }  // 返回事件类型标识 "text_end"
    }

    /**
     * 思考内容块开始事件，标记 LLM 开始展示思考过程（Chain-of-Thought）。
     * 当模型在最终回答之前展示内部推理过程时触发，常见于高级推理模型。
     * 思考内容通常对用户可见，用于展示模型的分析过程。
     *
     * @param contentIndex 内容块索引
     * @param partial      当前的 partial AssistantMessage
     */
    record ThinkingStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "thinking_start"; }  // 返回事件类型标识 "thinking_start"
    }

    /**
     * 思考增量事件，携带新增的思考文本片段。
     * 在 ThinkingStart 之后可能触发多次，逐步构建模型的推理过程内容。
     */
    record ThinkingDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "thinking_delta"; }  // 返回事件类型标识 "thinking_delta"
    }

    /**
     * 思考内容块结束事件，携带完整的思考内容。
     * 在 ThinkingDelta 全部发送完毕后触发，content 为完整思考过程文本。
     */
    record ThinkingEnd(int contentIndex, String content, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "thinking_end"; }  // 返回事件类型标识 "thinking_end"
    }

    /**
     * 工具调用内容块开始事件，标记 LLM 开始请求调用工具/函数。
     * 当模型决定调用某个工具（Function Calling）时触发，后续通过 delta 事件
     * 逐步传输工具调用的参数 JSON。
     */
    record ToolCallStart(int contentIndex, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "toolcall_start"; }  // 返回事件类型标识 "toolcall_start"
    }

    /**
     * 工具调用增量事件，携带新增的参数 JSON 片段。
     * 在 ToolCallStart 之后可能触发多次，每次携带参数 JSON 的一部分。
     * 消费者需要将多次 delta 拼接形成完整的 JSON 字符串后反序列化。
     */
    record ToolCallDelta(int contentIndex, String delta, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "toolcall_delta"; }  // 返回事件类型标识 "toolcall_delta"
    }

    /**
     * 工具调用内容块结束事件，携带解析完成的完整 ToolCall 对象。
     * 所有 ToolCallDelta 发送完毕后触发，此时 toolCall 参数已包含完整的
     * 工具名称、参数 JSON 等信息。
     */
    record ToolCallEnd(int contentIndex, ToolCall toolCall, AssistantMessage partial) implements AssistantMessageEvent {
        @Override
        public String type() { return "toolcall_end"; }  // 返回事件类型标识 "toolcall_end"
    }

    /**
     * 正常完成事件，表示 LLM 响应正常结束。
     * 携带停止原因（如 stop、end_turn、max_tokens 等）和最终完整的 AssistantMessage，
     * 包含所有内容块合并后的完整响应内容。
     */
    record Done(StopReason reason, AssistantMessage message) implements AssistantMessageEvent {
        @Override
        public String type() { return "done"; }  // 返回事件类型标识 "done"
    }

    /**
     * 错误终止事件，表示 LLM 响应因错误而终止。
     * 携带错误原因（如 content_filter、error 等）和包含错误信息的 AssistantMessage。
     * 消费者应检查此事件以处理流式响应中的异常情况。
     */
    record Error(StopReason reason, AssistantMessage error) implements AssistantMessageEvent {
        @Override
        public String type() { return "error"; }  // 返回事件类型标识 "error"
    }
}
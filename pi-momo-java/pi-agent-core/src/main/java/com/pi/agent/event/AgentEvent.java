package com.pi.agent.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentToolResult;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.types.ToolResultMessage;

import java.util.List;

/**
 * Agent 生命周期事件，在 Agent 循环运行过程中触发的一系列事件。
 *
 * <p>事件分为四大类别：
 * <ul>
 *   <li><b>Agent 生命周期</b>：{@link AgentStart}（Agent 开始）、{@link AgentEnd}（Agent 结束）</li>
 *   <li><b>Turn 生命周期</b>：{@link TurnStart}（Turn 开始）、{@link TurnEnd}（Turn 结束）</li>
 *   <li><b>消息生命周期</b>：{@link MessageStart}（消息开始）、{@link MessageUpdate}（消息更新）、{@link MessageEnd}（消息结束）</li>
 *   <li><b>工具执行生命周期</b>：{@link ToolExecutionStart}（工具执行开始）、{@link ToolExecutionUpdate}（工具执行更新）、{@link ToolExecutionEnd}（工具执行结束）</li>
 * </ul>
 *
 * <p>使用 Jackson 多态序列化，基于 {@code type} 字段作为鉴别器（discriminator）。
 * 序列化时将自动根据 type 值选择对应的子类型进行反序列化。
 *
 * <p><b>验证需求：11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 11.10</b>
 *
 * @see AgentEvent.AgentStart Agent 开始事件
 * @see AgentEvent.AgentEnd Agent 结束事件
 * @see AgentEvent.TurnStart Turn 开始事件
 * @see AgentEvent.TurnEnd Turn 结束事件
 * @see AgentEvent.MessageStart 消息开始事件
 * @see AgentEvent.MessageUpdate 消息更新事件
 * @see AgentEvent.MessageEnd 消息结束事件
 * @see AgentEvent.ToolExecutionStart 工具执行开始事件
 * @see AgentEvent.ToolExecutionUpdate 工具执行更新事件
 * @see AgentEvent.ToolExecutionEnd 工具执行结束事件
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = AgentEvent.AgentStart.class, name = "agent_start"),
    @JsonSubTypes.Type(value = AgentEvent.AgentEnd.class, name = "agent_end"),
    @JsonSubTypes.Type(value = AgentEvent.TurnStart.class, name = "turn_start"),
    @JsonSubTypes.Type(value = AgentEvent.TurnEnd.class, name = "turn_end"),
    @JsonSubTypes.Type(value = AgentEvent.MessageStart.class, name = "message_start"),
    @JsonSubTypes.Type(value = AgentEvent.MessageUpdate.class, name = "message_update"),
    @JsonSubTypes.Type(value = AgentEvent.MessageEnd.class, name = "message_end"),
    @JsonSubTypes.Type(value = AgentEvent.ToolExecutionStart.class, name = "tool_execution_start"),
    @JsonSubTypes.Type(value = AgentEvent.ToolExecutionUpdate.class, name = "tool_execution_update"),
    @JsonSubTypes.Type(value = AgentEvent.ToolExecutionEnd.class, name = "tool_execution_end")
})
public sealed interface AgentEvent
        permits AgentEvent.AgentStart,
                AgentEvent.AgentEnd,
                AgentEvent.TurnStart,
                AgentEvent.TurnEnd,
                AgentEvent.MessageStart,
                AgentEvent.MessageUpdate,
                AgentEvent.MessageEnd,
                AgentEvent.ToolExecutionStart,
                AgentEvent.ToolExecutionUpdate,
                AgentEvent.ToolExecutionEnd {

    /**
     * 获取事件类型的鉴别器字符串。
     * <p>该值将作为 JSON 序列化中的 {@code type} 字段，用于区分不同的事件子类型。
     *
     * @return 事件类型字符串，例如 "agent_start"、"turn_end" 等
     */
    String type();

    // ── Agent 生命周期 ──────────────────────────────────────────────────

    /**
     * Agent 循环开始事件。
     * <p>当 Agent 循环（agent loop）开始执行时触发，表示一轮完整的 Agent 处理流程的起点。
     * 该事件不携带任何额外数据，仅作为生命周期标记。
     */
    record AgentStart() implements AgentEvent {
        @Override
        public String type() { return "agent_start"; }
    }

    /**
     * Agent 循环结束事件。
     * <p>当 Agent 循环执行完毕时触发，表示一轮完整的 Agent 处理流程的终点。
     *
     * @param messages 本次运行期间产生的所有新消息列表
     */
    record AgentEnd(List<AgentMessage> messages) implements AgentEvent {
        @Override
        public String type() { return "agent_end"; }
    }

    // ── Turn 生命周期 ───────────────────────────────────────────────────

    /**
     * Turn 开始事件。
     * <p>每个 Turn 代表一次 LLM 调用及其可能伴随的工具执行过程。
     * 一个 Agent 运行周期可能包含多个 Turn，直到达到停止条件为止。
     */
    record TurnStart() implements AgentEvent {
        @Override
        public String type() { return "turn_start"; }
    }

    /**
     * Turn 结束事件。
     * <p>当一次 LLM 调用及其相关的工具执行全部完成后触发。
     *
     * @param message     本次 Turn 产生的助手消息（Assistant Message）
     * @param toolResults 本次 Turn 中产生的工具执行结果消息列表，如果没有工具执行则为空列表
     */
    record TurnEnd(AgentMessage message, List<ToolResultMessage> toolResults) implements AgentEvent {
        @Override
        public String type() { return "turn_end"; }
    }

    // ── 消息生命周期 ────────────────────────────────────────────────

    /**
     * 消息开始事件。
     * <p>当一条新消息（用户消息、助手消息或工具结果消息）被添加到对话中时触发。
     *
     * @param message 正在被添加的消息对象
     */
    record MessageStart(AgentMessage message) implements AgentEvent {
        @Override
        public String type() { return "message_start"; }
    }

    /**
     * 消息更新事件。
     * <p>在助手消息流式输出（streaming）过程中，每次收到增量更新时触发。
     * 适用于实现实时流式展示助手回复内容的场景。
     *
     * @param message               当前累积的部分助手消息对象
     * @param assistantMessageEvent 底层 LLM 流式事件对象，包含最新的增量数据
     */
    record MessageUpdate(AgentMessage message, AssistantMessageEvent assistantMessageEvent) implements AgentEvent {
        @Override
        public String type() { return "message_update"; }
    }

    /**
     * 消息结束事件。
     * <p>当一条消息在对话中最终确定（不再有更新）时触发。
     * 表示该消息的整个生命周期已完成。
     *
     * @param message 最终确定的消息对象
     */
    record MessageEnd(AgentMessage message) implements AgentEvent {
        @Override
        public String type() { return "message_end"; }
    }

    // ── 工具执行生命周期 ─────────────────────────────────────────

    /**
     * 工具执行开始事件。
     * <p>当 Agent 决定调用某个工具并开始执行时触发。
     * 此时工具的参数已经过校验（validation），可以安全使用。
     *
     * @param toolCallId 此次工具调用的唯一标识符，用于关联开始、更新和结束事件
     * @param toolName   被执行的工具名称
     * @param args       经过校验后的工具参数对象
     */
    record ToolExecutionStart(String toolCallId, String toolName, Object args) implements AgentEvent {
        @Override
        public String type() { return "tool_execution_start"; }
    }

    /**
     * 工具执行更新事件。
     * <p>在工具执行过程中，当工具通过回调（onUpdate）返回部分结果时触发。
     * 适用于需要实时展示工具执行进度的场景。
     *
     * @param toolCallId    此次工具调用的唯一标识符
     * @param toolName      被执行的工具名称
     * @param args          经过校验后的工具参数对象
     * @param partialResult 工具通过 onUpdate 回调返回的部分结果
     */
    record ToolExecutionUpdate(String toolCallId, String toolName, Object args, AgentToolResult<?> partialResult) implements AgentEvent {
        @Override
        public String type() { return "tool_execution_update"; }
    }

    /**
     * 工具执行结束事件。
     * <p>当工具执行完成（无论成功或失败）时触发，标记此次工具调用的终结。
     *
     * @param toolCallId 此次工具调用的唯一标识符
     * @param toolName   被执行的工具名称
     * @param result     工具执行的最终结果对象
     * @param isError    标记工具执行是否发生了错误，{@code true} 表示执行出错
     */
    record ToolExecutionEnd(String toolCallId, String toolName, AgentToolResult<?> result, boolean isError) implements AgentEvent {
        @Override
        public String type() { return "tool_execution_end"; }
    }
}
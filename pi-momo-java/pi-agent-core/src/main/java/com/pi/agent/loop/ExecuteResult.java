package com.pi.agent.loop;

import com.pi.agent.types.AgentToolResult;

/**
 * 执行已准备好的工具调用的结果。
 *
 * <p>该记录（Record）封装了工具执行后的最终产出，包含两个核心字段：
 * <ul>
 *   <li>{@code result} — 工具执行后返回的结果对象，泛型 {@link AgentToolResult} 可承载任意类型的内容及详情信息。</li>
 *   <li>{@code isError} — 标识此次执行是否发生了错误。若为 {@code true}，则 {@code result} 中应包含错误描述信息。</li>
 * </ul>
 *
 * <p>该记录由 {@link AgentLoop#executePreparedToolCall} 方法创建，
 * 并在 {@link AgentLoop#executeToolCallsSequential} 和
 * {@link AgentLoop#executeToolCallsParallel} 中被消费，
 * 最终由 {@link AgentLoop#finalizeExecutedToolCall} 方法完成后处理（如调用 AfterToolCallHook、发射事件等）。
 *
 * <p><b>验证需求：21.3, 21.4</b>
 *
 * @param result  工具执行结果，包含内容（content）和详情（details）两部分
 * @param isError 是否发生错误；{@code true} 表示执行失败，result 中应包含错误信息
 */
record ExecuteResult(AgentToolResult<?> result, boolean isError) {}

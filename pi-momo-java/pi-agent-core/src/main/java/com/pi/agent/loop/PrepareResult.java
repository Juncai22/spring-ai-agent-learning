package com.pi.agent.loop;

import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.ai.core.types.ToolCall;

/**
 * 工具调用准备阶段的结果类型 —— 密封接口（sealed interface），
 * 仅允许两种子类型：{@link Prepared} 和 {@link Immediate}。
 *
 * <p>在 Agent 循环中，每个工具在正式执行之前都需要经过一系列准备检查：
 * <ol>
 *   <li>查找工具：根据工具调用名称（{@code toolCall.name()}）在上下文的工具列表中匹配对应的 {@link AgentTool}。</li>
 *   <li>参数校验：使用 {@code ToolValidator.validateToolArguments} 校验工具调用的参数是否符合 JSON Schema 定义。</li>
 *   <li>前置钩子：调用可配置的 {@code BeforeToolCallHook}，允许业务方在工具执行前进行拦截、审批或修改参数。</li>
 * </ol>
 *
 * <p>若所有检查通过，则返回 {@link Prepared}，表示工具已准备好执行；
 * 若任一检查失败（工具未找到、参数校验失败、或被前置钩子阻止），则返回 {@link Immediate}，
 * 其中包含一个错误结果，可直接返回给 LLM 而无需继续执行。
 *
 * <p>该密封设计确保了在 {@code switch} 或 {@code if-else instanceof} 中能够穷尽所有可能的分支，
 * 避免遗漏处理某种准备结果。
 *
 * <p><b>验证需求：20.1, 20.2, 20.3, 20.4, 20.5, 20.6, 20.7</b>
 *
 * @see AgentLoop#prepareToolCall 准备工具调用的核心方法
 * @see AgentLoop#executeToolCallsSequential 顺序执行场景下消费 PrepareResult
 * @see AgentLoop#executeToolCallsParallel 并行执行场景下消费 PrepareResult
 */
sealed interface PrepareResult permits PrepareResult.Prepared, PrepareResult.Immediate {

    /**
     * 准备就绪状态 —— 工具调用已通过所有检查，可以执行。
     *
     * <p>当工具查找成功、参数校验通过、且前置钩子（如有）未阻止时，
     * {@link AgentLoop#prepareToolCall} 方法返回此实例。
     *
     * <p>该记录保留了准备阶段的所有关键信息，供后续执行阶段使用：
     * <ul>
     *   <li>{@code toolCall} — 原始的 LLM 工具调用请求，包含调用 ID 和参数信息；</li>
     *   <li>{@code tool} — 解析后的 {@link AgentTool} 实例，实际执行逻辑由此对象完成；</li>
     *   <li>{@code args} — 校验通过后的参数对象，与原始参数可能相同（未修改时）。</li>
     * </ul>
     *
     * @param toolCall 来自 AssistantMessage 的原始工具调用（包含 toolCallId、工具名称、参数）
     * @param tool     已解析的 AgentTool 实例，用于实际执行工具逻辑
     * @param args     校验通过后的参数对象，可能经过 BeforeToolCallHook 修改
     */
    record Prepared(ToolCall toolCall, AgentTool tool, Object args) implements PrepareResult {}

    /**
     * 立即返回状态 —— 工具调用准备失败，包含可直接返回给 LLM 的错误结果。
     *
     * <p>在以下任一场景中返回此实例：
     * <ul>
     *   <li>未找到对应名称的工具（工具不在上下文的工具列表中）；</li>
     *   <li>工具调用参数校验失败（参数格式错误、缺少必填字段等）；</li>
     *   <li>前置钩子（BeforeToolCallHook）阻止了工具执行（如安全策略拦截、参数审核未通过）；</li>
     *   <li>前置钩子执行过程中抛出异常。</li>
     * </ul>
     *
     * <p>返回此结果后，调用方会直接将 {@code result} 组装为 {@code ToolResultMessage}，
     * 发射 message_start/message_end 事件，然后追加到上下文中，无需再走执行流程。
     *
     * @param result  错误工具结果，内容（content）中包含错误描述信息，详情（details）可为空
     * @param isError 固定为 {@code true}，表示这是一个错误结果，LLM 收到后可根据错误信息调整行为
     */
    record Immediate(AgentToolResult<?> result, boolean isError) implements PrepareResult {}
}

package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.config.BeforeToolCallHook;
import com.pi.agent.config.ConvertToLlmFunction;
import com.pi.agent.config.StreamFn;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.BeforeToolCallContext;
import com.pi.agent.types.BeforeToolCallResult;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.event.EventStream;
import com.pi.ai.core.stream.PiAi;
import com.pi.ai.core.types.AssistantContentBlock;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Context;
import com.pi.ai.core.types.Message;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.SimpleStreamOptions;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.Tool;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.ToolResultMessage;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.util.PiAiJson;
import com.pi.ai.core.util.ToolValidator;

import com.pi.agent.config.AfterToolCallHook;
import com.pi.agent.types.AfterToolCallContext;
import com.pi.agent.types.AfterToolCallResult;
import com.pi.agent.types.ToolExecutionMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agent 循环引擎 —— 核心的 LLM Agent 驱动循环。
 *
 * <p>该类提供了两个静态入口方法，用于启动和继续 Agent 的对话：
 * <ul>
 *   <li>{@link #agentLoop} — 启动一个新的 Agent 对话循环，接收新的提示消息列表作为输入；</li>
 *   <li>{@link #agentLoopContinue} — 在已有上下文中继续 Agent 对话循环，不添加新消息（如重试场景）。</li>
 * </ul>
 *
 * <p>核心循环逻辑（{@link #runLoop}）采用双层循环结构：
 * <ul>
 *   <li><b>外层循环</b>：处理 FollowUp 消息 —— 当 Agent 即将停止时，检查是否有排队待处理的后续消息；
 *       如果有，则继续循环处理；否则正常结束。</li>
 *   <li><b>内层循环</b>：处理工具调用和 Steering 消息 —— 只要还有待处理的工具调用或注入的 Steering 消息，
 *       就持续工作：发送请求给 LLM → 解析响应 → 执行工具调用 → 注入结果 → 继续下一轮。</li>
 * </ul>
 *
 * <p>该类为纯工具类（Utility Class），所有方法均为静态方法，构造方法私有化，禁止实例化。
 *
 * <p><b>验证需求：14.1, 14.2, 14.3, 14.4, 14.5, 15.1, 15.2, 15.3, 15.4, 15.5,
 * 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7, 16.8, 16.9, 16.10</b>
 *
 * @see AgentLoopConfig 循环配置项
 * @see AgentContext Agent 上下文（系统提示、消息列表、工具列表）
 * @see EventStream 事件流机制，用于将 Agent 运行过程中的事件推送给调用方
 */
public final class AgentLoop {

    /**
     * 私有构造方法，防止外部实例化。
     * AgentLoop 是一个纯静态工具类，所有方法均通过类名直接调用。
     */
    private AgentLoop() {
        // 工具类 —— 禁止实例化
    }

    /**
     * 启动 Agent 循环 —— 以新的提示消息开始一次 Agent 对话。
     *
     * <p>该方法接收一个或多个提示消息（{@code prompts}），将它们追加到 Agent 上下文的
     * 消息列表中，然后启动完整的 Agent 循环。返回的 {@link EventStream} 会在循环过程中
     * 发射一系列 {@link AgentEvent} 事件（如 AgentStart、TurnStart、MessageStart、
     * MessageEnd、ToolExecutionStart 等），并在循环结束时完成（complete），
     * 提供本次运行中产生的所有新消息列表（包括提示消息、助手的回复消息、工具执行结果消息）。
     *
     * <p><b>异步执行</b>：该方法使用 {@link CompletableFuture#runAsync} 在异步线程中执行
     * 循环逻辑，因此调用方不会阻塞。异常处理在异步线程内部完成：当捕获到异常时，
     * 会构造一个包含错误信息的 AssistantMessage，发射 MessageEnd 和 AgentEnd 事件，
     * 然后以该错误消息列表结束事件流，而不是向上传播异常。
     *
     * <p><b>验证需求：14.1, 14.2, 14.3, 14.4, 14.5</b>
     *
     * @param prompts  要发送的提示消息列表，每条消息会触发 MessageStart/MessageEnd 事件
     * @param context  Agent 上下文，包含系统提示（system prompt）、历史消息列表和可用工具列表
     * @param config   Agent 循环配置，包括模型、流式选项、转换函数、钩子函数等
     * @param signal   可选的取消信号，用于在外部取消正在进行的 Agent 循环
     * @param streamFn 可选的流式函数，默认使用 {@code PiAi::streamSimple} 实现 LLM 调用
     * @return 一个事件流，在循环过程中发射 AgentEvent 事件，在完成时提供所有新消息的列表
     */
    public static EventStream<AgentEvent, List<AgentMessage>> agentLoop(
            List<AgentMessage> prompts,
            AgentContext context,
            AgentLoopConfig config,
            CancellationSignal signal,
            StreamFn streamFn) {

        EventStream<AgentEvent, List<AgentMessage>> stream = createAgentStream();

        CompletableFuture.runAsync(() -> {
            try {
                List<AgentMessage> messages = runAgentLoop(
                        prompts, context, config, stream, signal, streamFn);
                stream.end(messages);
            } catch (Exception e) {
                // 异常处理：构造一个包含错误信息的 AssistantMessage，确保事件流正常结束
                Model model = config.getModel();
                AssistantMessage errorMsg = AssistantMessage.builder()
                        .content(List.of(new TextContent("")))
                        .api(model != null ? model.api() : null)
                        .provider(model != null ? model.provider() : null)
                        .model(model != null ? model.id() : null)
                        .usage(new Usage(0, 0, 0, 0, 0, new Usage.Cost(0.0, 0.0, 0.0, 0.0, 0.0)))
                        .stopReason(StopReason.ERROR)
                        .errorMessage(e.getMessage())
                        .timestamp(System.currentTimeMillis())
                        .build();
                AgentMessage wrappedErrorMsg = MessageAdapter.wrap(errorMsg);
                // 发射 MessageEnd 和 AgentEnd 事件，然后以错误消息列表结束事件流
                stream.push(new AgentEvent.MessageEnd(wrappedErrorMsg));
                stream.push(new AgentEvent.AgentEnd(List.of(wrappedErrorMsg)));
                stream.end(List.of(wrappedErrorMsg));
            }
        });

        return stream;
    }


    /**
     * 继续 Agent 循环 —— 在不添加新消息的情况下，基于当前上下文继续执行。
     *
     * <p>该方法主要用于重试（retry）场景：当上一次循环因错误或中断而结束时，
     * 上下文（{@code context}）中已经包含了用户消息或工具结果消息，
     * 调用此方法可以继续驱动 LLM 产生回应。
     *
     * <p><b>前置条件校验</b>：
     * <ul>
     *   <li>上下文的消息列表不能为空，否则抛出 {@link IllegalStateException}；</li>
     *   <li>最后一条消息的 role 不能是 "assistant"，否则抛出 {@link IllegalStateException}，
     *       因为继续循环需要 LLM 来回应，而最后一条消息如果是助理消息则表示没有需要回应的内容。</li>
     * </ul>
     *
     * <p>与 {@link #agentLoop} 不同，此方法不会将新消息追加到上下文，而是直接
     * 进入循环逻辑（{@link #runLoop}），由 LLM 根据上下文中的最后一条消息（通常是
     * 工具结果或用户消息）产生响应。
     *
     * <p><b>验证需求：15.1, 15.2, 15.3, 15.4, 15.5</b>
     *
     * @param context  Agent 上下文，必须包含至少一条消息，且最后一条消息的 role 不能是 assistant
     * @param config   Agent 循环配置
     * @param signal   可选的取消信号
     * @param streamFn 可选的流式函数，默认使用 PiAi::streamSimple
     * @return 一个事件流，在循环过程中发射 AgentEvent 事件，在完成时提供所有新消息的列表
     * @throws IllegalStateException 如果消息列表为空，或者最后一条消息的 role 是 assistant
     */
    public static EventStream<AgentEvent, List<AgentMessage>> agentLoopContinue(
            AgentContext context,
            AgentLoopConfig config,
            CancellationSignal signal,
            StreamFn streamFn) {

        if (context.getMessages().isEmpty()) {
            throw new IllegalStateException("Cannot continue: no messages in context");
        }

        AgentMessage lastMessage = context.getMessages().get(context.getMessages().size() - 1);
        if ("assistant".equals(lastMessage.role())) {
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }

        EventStream<AgentEvent, List<AgentMessage>> stream = createAgentStream();

        CompletableFuture.runAsync(() -> {
            try {
                List<AgentMessage> messages = runAgentLoopContinue(
                        context, config, stream, signal, streamFn);
                stream.end(messages);
            } catch (Exception e) {
                // 异常处理：以空消息列表结束事件流，确保调用方不会因异常而阻塞
                stream.end(List.of());
            }
        });

        return stream;
    }

    // ── 内部方法 ─────────────────────────────────────────────────

    /**
     * 创建 Agent 事件流，并配置完成检测逻辑。
     *
     * <p>该方法返回的 {@link EventStream} 通过两个 Lambda 表达式定义流的完成行为：
     * <ul>
     *   <li><b>完成检测器</b>（第一个参数）：当事件类型为 {@link AgentEvent.AgentEnd} 时，
     *       判定流已完成。AgentEnd 是 Agent 循环正常结束或异常结束的标志事件。</li>
     *   <li><b>结果提取器</b>（第二个参数）：当流完成时，从 AgentEnd 事件中提取所有新消息
     *       列表作为最终结果。如果事件不是 AgentEnd 类型，则返回空列表。</li>
     * </ul>
     *
     * <p>这种设计使得 EventStream 的消费者可以通过 {@code .join()} 等待整个 Agent 循环
     * 完成，并直接获取所有新产生的消息，而不需要手动监听事件。
     *
     * @return 配置了完成检测和结果提取逻辑的 Agent 事件流
     */
    static EventStream<AgentEvent, List<AgentMessage>> createAgentStream() {
        return new EventStream<>(
                event -> event instanceof AgentEvent.AgentEnd,
                event -> event instanceof AgentEvent.AgentEnd agentEnd
                        ? agentEnd.messages()
                        : List.of()
        );
    }

    /**
     * 内部方法：运行带新提示消息的 Agent 循环。
     *
     * <p>处理流程：
     * <ol>
     *   <li>将新提示消息列表复制到 {@code newMessages} 中（用于跟踪本次运行产生的所有消息）；</li>
     *   <li>将每条提示消息追加到上下文的消息列表中，以便 LLM 在生成响应时能看到这些消息；</li>
     *   <li>发射生命周期事件：AgentStart → TurnStart；</li>
     *   <li>为每条提示消息发射 MessageStart/MessageEnd 事件；</li>
     *   <li>委托给 {@link #runLoop} 执行核心循环逻辑。</li>
     * </ol>
     *
     * @param prompts  要发送的提示消息列表
     * @param context  Agent 上下文（消息列表会被修改——追加提示消息）
     * @param config   Agent 循环配置
     * @param stream   事件流，用于发射 Agent 事件
     * @param signal   可选的取消信号
     * @param streamFn 可选的流式函数
     * @return 本次运行中产生的所有新消息列表（包括提示消息、助理消息、工具结果消息）
     */
    static List<AgentMessage> runAgentLoop(
            List<AgentMessage> prompts,
            AgentContext context,
            AgentLoopConfig config,
            EventStream<AgentEvent, List<AgentMessage>> stream,
            CancellationSignal signal,
            StreamFn streamFn) {

        List<AgentMessage> newMessages = new ArrayList<>(prompts);

        // Append prompts to context messages
        for (AgentMessage prompt : prompts) {
            context.getMessages().add(prompt);
        }

        // Emit lifecycle events
        stream.push(new AgentEvent.AgentStart());
        stream.push(new AgentEvent.TurnStart());

        // Emit message_start/message_end for each prompt
        for (AgentMessage prompt : prompts) {
            stream.push(new AgentEvent.MessageStart(prompt));
            stream.push(new AgentEvent.MessageEnd(prompt));
        }

        // Delegate to runLoop (stub for now — Task 7.2 will implement the full logic)
        runLoop(context, newMessages, config, signal, stream, streamFn);

        return newMessages;
    }

    /**
     * 内部方法：运行继续模式下的 Agent 循环（不添加新提示消息）。
     *
     * <p>与 {@link #runAgentLoop} 不同，此方法不会将新消息追加到上下文，而是
     * 直接从上下文中已有的消息开始驱动循环。适用于重试场景或上下文已包含
     * 用户消息/工具结果消息的情况。
     *
     * <p>处理流程：
     * <ol>
     *   <li>创建空的 {@code newMessages} 列表，用于跟踪本次运行产生的新消息；</li>
     *   <li>发射生命周期事件：AgentStart → TurnStart；</li>
     *   <li>委托给 {@link #runLoop} 执行核心循环逻辑。</li>
     * </ol>
     *
     * @param context  Agent 上下文（消息列表不能为空，且最后一条消息不能是 assistant 消息）
     * @param config   Agent 循环配置
     * @param stream   事件流，用于发射 Agent 事件
     * @param signal   可选的取消信号
     * @param streamFn 可选的流式函数
     * @return 本次运行中产生的所有新消息列表（包括助理消息、工具结果消息等）
     */
    static List<AgentMessage> runAgentLoopContinue(
            AgentContext context,
            AgentLoopConfig config,
            EventStream<AgentEvent, List<AgentMessage>> stream,
            CancellationSignal signal,
            StreamFn streamFn) {

        List<AgentMessage> newMessages = new ArrayList<>();

        // Emit lifecycle events
        stream.push(new AgentEvent.AgentStart());
        stream.push(new AgentEvent.TurnStart());

        // Delegate to runLoop (stub for now — Task 7.2 will implement the full logic)
        runLoop(context, newMessages, config, signal, stream, streamFn);

        return newMessages;
    }

    /**
     * 核心循环逻辑 —— Agent 循环的主引擎。
     *
     * <p>该方法被 {@link #runAgentLoop} 和 {@link #runAgentLoopContinue} 共同调用，
     * 实现了 Agent 驱动 LLM 的最核心循环逻辑。采用<b>双层循环结构</b>：
     *
     * <h3>外层循环（FollowUp 处理）</h3>
     * <p>Agent 在内层循环结束后（即没有更多工具调用和 Steering 消息时）会进入"即将停止"状态。
     * 此时，外层循环通过 {@link #pollFollowUpMessages} 检查是否有排队的后续消息（FollowUp）。
     * 如果有，则将这些消息设为待处理状态，并继续内层循环；如果没有，则正常退出循环并发射 AgentEnd 事件。
     *
     * <h3>内层循环（工具调用和 Steering 处理）</h3>
     * <p>只要还有待处理的工具调用（{@code hasMoreToolCalls}）或注入的 Steering 消息
     * （{@code pendingMessages}），内层循环就会持续工作。每个轮次（Turn）的处理流程如下：
     * <ol>
     *   <li>发射 TurnStart 事件（非首轮）；</li>
     *   <li>处理待处理的 Steering 消息（注入到上下文并发射事件）；</li>
     *   <li>调用 {@link #streamAssistantResponse} 从 LLM 获取流式响应；</li>
     *   <li>检查响应的停止原因（StopReason）：
     *       <ul>
     *         <li>ERROR 或 ABORTED → 立即终止循环，发射 TurnEnd 和 AgentEnd；</li>
     *         <li>其他 → 继续处理。</li>
     *       </ul></li>
     *   <li>提取工具调用列表：
     *       <ul>
     *         <li>如果有工具调用 → 调用 {@link #executeToolCalls} 执行所有工具；</li>
     *         <li>如果没有 → 设置 {@code hasMoreToolCalls = false}。</li>
     *       </ul></li>
     *   <li>将工具执行结果追加到上下文中并发射事件；</li>
     *   <li>发射 TurnEnd 事件；</li>
     *   <li>轮询 Steering 消息，为下一轮做准备。</li>
     * </ol>
     *
     * <h3>Steering 消息机制</h3>
     * <p>Steering 消息允许外部系统在 Agent 循环运行过程中注入消息。
     * 例如，用户在 Agent 思考过程中输入了新的指令，这些指令会通过
     * {@link #pollSteeringMessages} 被轮询并注入到下一轮 LLM 请求中。
     * 内层循环的每次迭代结束后都会检查是否有新的 Steering 消息。
     *
     * <h3>FollowUp 消息机制</h3>
     * <p>FollowUp 消息是在 Agent 即将停止（内层循环结束）时注入的消息，
     * 用于实现"后续追问"等场景。与外层循环配合，确保 Agent 在停止前
     * 能处理完所有后续消息。
     *
     * <p><b>验证需求：16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7, 16.8, 16.9, 16.10</b>
     *
     * @param context    Agent 上下文（可变，循环过程中会不断追加新消息）
     * @param newMessages 新消息列表（可变，用于收集本次运行产生的所有新消息）
     * @param config     Agent 循环配置
     * @param signal     可选的取消信号
     * @param stream     事件流，用于发射所有 Agent 事件
     * @param streamFn   可选的流式函数
     */
    static void runLoop(
            AgentContext context,
            List<AgentMessage> newMessages,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream,
            StreamFn streamFn) {

        boolean firstTurn = true;

        // 检查是否有注入的 Steering 消息（例如用户在等待过程中输入的新指令）
        List<AgentMessage> pendingMessages = pollSteeringMessages(config);

        // 外层循环：处理 FollowUp 消息 —— 当 Agent 即将停止时，检查是否有排队待处理的后续消息
        while (true) {
            boolean hasMoreToolCalls = true;

            // 内层循环：处理工具调用和 Steering 消息
            // 只要还有待处理的工具调用或注入的 Steering 消息，就持续工作
            while (hasMoreToolCalls || !pendingMessages.isEmpty()) {

                // 非首轮需要发射 turn_start 事件（首轮的 turn_start 由调用方 emit）
                if (!firstTurn) {
                    stream.push(new AgentEvent.TurnStart());
                } else {
                    firstTurn = false;
                }

                // 处理待处理的 Steering 消息（在下一轮 LLM 请求注入之前处理）
                if (!pendingMessages.isEmpty()) {
                    for (AgentMessage msg : pendingMessages) {
                        stream.push(new AgentEvent.MessageStart(msg));
                        stream.push(new AgentEvent.MessageEnd(msg));
                        context.getMessages().add(msg);
                        newMessages.add(msg);
                    }
                    pendingMessages = new ArrayList<>();
                }

                // 流式获取 LLM 的助理响应（骨架实现，后续任务将完善完整逻辑）
                AgentMessage assistantAgentMsg = streamAssistantResponse(
                        context, config, signal, stream, streamFn);
                newMessages.add(assistantAgentMsg);

                // 检查停止原因：如果是 ERROR 或 ABORTED，立即终止循环
                StopReason stopReason = extractStopReason(assistantAgentMsg);
                if (stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED) {
                    stream.push(new AgentEvent.TurnEnd(assistantAgentMsg, List.of()));
                    stream.push(new AgentEvent.AgentEnd(newMessages));
                    return;
                }

                // 检查助理消息中是否包含工具调用
                List<ToolCall> toolCalls = extractToolCalls(assistantAgentMsg);
                hasMoreToolCalls = !toolCalls.isEmpty();

                List<ToolResultMessage> toolResults = new ArrayList<>();
                if (hasMoreToolCalls) {
                    // 执行工具调用（骨架实现，后续任务将完善完整逻辑）
                    toolResults.addAll(executeToolCalls(
                            context, assistantAgentMsg, config, signal, stream));

                    // 将工具执行结果追加到上下文和新消息列表中
                    for (ToolResultMessage result : toolResults) {
                        AgentMessage wrappedResult = MessageAdapter.wrap(result);
                        context.getMessages().add(wrappedResult);
                        newMessages.add(wrappedResult);
                    }
                }

                // 发射 turn_end 事件，包含助理消息和工具执行结果
                stream.push(new AgentEvent.TurnEnd(assistantAgentMsg, toolResults));

                // 每次轮次结束后轮询是否有新的 Steering 消息
                pendingMessages = pollSteeringMessages(config);
            }

            // 内层循环结束，Agent 即将停止。检查是否有 FollowUp 后续消息。
            List<AgentMessage> followUpMessages = pollFollowUpMessages(config);
            if (!followUpMessages.isEmpty()) {
                // 将 FollowUp 消息设为待处理状态，继续外层循环
                pendingMessages = followUpMessages;
                continue;
            }

            // 没有更多消息，退出外层循环
            break;
        }

        // 正常完成 —— 发射 agent_end 事件
        stream.push(new AgentEvent.AgentEnd(newMessages));
    }

    // ── 骨架方法（由后续任务实现完整逻辑） ─────────────────────────────

    /**
     * 从 LLM 流式获取助理响应。
     *
     * <p>该方法是 Agent 循环中与 LLM 交互的核心环节，实现了一个完整的调用流水线：
     *
     * <ol>
     *   <li><b>上下文转换（可选）</b>：如果配置了 {@code transformContext}，
     *       则对 AgentMessage 列表进行转换（如过滤、排序、重新组织）；</li>
     *   <li><b>消息转换</b>：将 AgentMessage 列表转换为 LLM 可理解的 Message 列表；
     *       如果有自定义的 {@code convertToLlm} 函数则使用，否则默认过滤并解包
     *       {@link MessageAdapter} 实例；</li>
     *   <li><b>构建 LLM 上下文</b>：将系统提示、消息列表和工具列表组装为 LLM 的 Context 对象；</li>
     *   <li><b>解析 API Key</b>：如果配置了 {@code getApiKey} 回调，则动态获取 API Key，
     *       否则使用配置中的静态 API Key；</li>
     *   <li><b>调用流式函数</b>：使用配置的模型和流式选项调用 LLM；</li>
     *   <li><b>处理事件流</b>：监听 LLM 返回的事件流，处理 Start/Delta/Done/Error 事件，
     *       同时发射对应的 AgentEvent 事件。</li>
     * </ol>
     *
     * <p>事件流处理策略：
     * <ul>
     *   <li>{@link AssistantMessageEvent.Start} — 将部分消息包装后添加到上下文，发射 MessageStart 事件；</li>
     *   <li>Delta 事件（TextStart/TextDelta/TextEnd, ThinkingStart/ThinkingDelta/ThinkingEnd,
     *       ToolCallStart/ToolCallDelta/ToolCallEnd）— 更新上下文中的最后一条消息，并发射 MessageUpdate 事件；</li>
     *   <li>{@link AssistantMessageEvent.Done} — 替换上下文中的部分消息为最终消息，发射 MessageEnd 事件；</li>
     *   <li>{@link AssistantMessageEvent.Error} — 同 Done 处理，但最终消息会包含错误信息。</li>
     * </ul>
     *
     * <p><b>验证需求：17.1, 17.2, 17.3, 17.4, 17.5, 17.6, 17.7, 17.8, 17.9</b>
     *
     * @param context  Agent 上下文，包含系统提示、消息列表和工具列表
     * @param config   Agent 循环配置
     * @param signal   可选的取消信号
     * @param stream   事件流，用于发射 Agent 事件
     * @param streamFn 可选的流式函数，默认使用 {@code PiAi::streamSimple}
     * @return 包装为 AgentMessage 的最终助理消息
     */
    static AgentMessage streamAssistantResponse(
            AgentContext context,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream,
            StreamFn streamFn) {

        // 1. 上下文转换（可选）：如果配置了 transformContext，则对消息列表进行转换（验证需求 17.1, 17.2）
        List<AgentMessage> messages = context.getMessages();
        if (config.getTransformContext() != null) {
            try {
                messages = config.getTransformContext().transform(messages, signal).join();
            } catch (Exception e) {
                // transformContext 不应抛出异常；如果抛出，则回退到原始消息列表
                messages = context.getMessages();
            }
        }

        // 2. 转换为 LLM 消息格式（验证需求 17.3）
        // 优先使用自定义转换函数，否则默认过滤并解包 MessageAdapter 实例
        ConvertToLlmFunction convertFn = config.getConvertToLlm();
        List<Message> llmMessages;
        if (convertFn != null) {
            llmMessages = convertFn.convert(messages);
        } else {
            // 默认行为：过滤出 MessageAdapter 实例并解包为 LLM Message
            llmMessages = messages.stream()
                    .filter(MessageAdapter::isLlmMessage)
                    .map(MessageAdapter::unwrap)
                    .collect(Collectors.toList());
        }

        // 3. 构建 LLM 上下文（验证需求 17.4）
        // 将 AgentTool 列表转换为 LLM Tool 列表，组装系统提示、消息和工具
        List<Tool> tools = null;
        if (context.getTools() != null && !context.getTools().isEmpty()) {
            tools = context.getTools().stream()
                    .map(AgentTool::toTool)
                    .collect(Collectors.toList());
        }
        Context llmContext = new Context(context.getSystemPrompt(), llmMessages, tools);

        // 4. 解析 API Key（验证需求 17.5）
        // 优先使用动态获取的 API Key（通过 getApiKey 回调），否则使用静态配置的 API Key
        SimpleStreamOptions baseOptions = config.getStreamOptions();
        String resolvedApiKey = baseOptions.getApiKey();
        if (config.getGetApiKey() != null && config.getModel() != null) {
            try {
                String dynamicKey = config.getGetApiKey().getApiKey(config.getModel().provider()).join();
                if (dynamicKey != null) {
                    resolvedApiKey = dynamicKey;
                }
            } catch (Exception e) {
                // 动态获取失败时回退到静态 API Key
            }
        }

        // 构建流式选项：合并解析后的 API Key 和取消信号，保留其他配置项
        SimpleStreamOptions effectiveOptions = SimpleStreamOptions.simpleBuilder()
                .temperature(baseOptions.getTemperature())
                .maxTokens(baseOptions.getMaxTokens())
                .apiKey(resolvedApiKey)
                .cacheRetention(baseOptions.getCacheRetention())
                .sessionId(baseOptions.getSessionId())
                .headers(baseOptions.getHeaders())
                .transport(baseOptions.getTransport())
                .maxRetryDelayMs(baseOptions.getMaxRetryDelayMs())
                .metadata(baseOptions.getMetadata())
                .onPayload(baseOptions.getOnPayload())
                .signal(signal != null ? signal : baseOptions.getSignal())
                .reasoning(baseOptions.getReasoning())
                .thinkingBudgets(baseOptions.getThinkingBudgets())
                .build();

        // 5. 调用流式函数（验证需求 12.2）
        // 默认使用 PiAi::streamSimple，可通过 streamFn 参数自定义
        StreamFn fn = streamFn != null ? streamFn : PiAi::streamSimple;
        AssistantMessageEventStream response = fn.stream(config.getModel(), llmContext, effectiveOptions);

        // 6. 处理事件流（验证需求 17.6, 17.7, 17.8, 17.9）
        // 遍历 LLM 返回的事件流，逐个处理 Start、Delta、Done、Error 事件
        AgentMessage partialAgentMsg = null;
        boolean addedPartial = false;

        for (AssistantMessageEvent event : response) {
            if (event instanceof AssistantMessageEvent.Start) {
                // 处理 Start 事件：验证需求 17.6
                // 将部分消息包装后添加到上下文，并发射 MessageStart 事件
                AssistantMessageEvent.Start start = (AssistantMessageEvent.Start) event;
                partialAgentMsg = MessageAdapter.wrap(start.partial());
                context.getMessages().add(partialAgentMsg);
                addedPartial = true;
                stream.push(new AgentEvent.MessageStart(partialAgentMsg));

            } else if (event instanceof AssistantMessageEvent.Done) {
                // 处理 Done 事件：验证需求 17.8
                // 获取最终消息，替换上下文中的部分消息（如果有），发射 MessageEnd 事件
                AssistantMessage finalMsg = response.result().join();
                AgentMessage finalAgentMsg = MessageAdapter.wrap(finalMsg);
                if (addedPartial) {
                    replaceLastMessage(context, finalAgentMsg);
                } else {
                    context.getMessages().add(finalAgentMsg);
                    stream.push(new AgentEvent.MessageStart(finalAgentMsg));
                }
                stream.push(new AgentEvent.MessageEnd(finalAgentMsg));
                return finalAgentMsg;

            } else if (event instanceof AssistantMessageEvent.Error) {
                // 处理 Error 事件：验证需求 17.8
                // 与 Done 处理方式相同，但最终消息会包含错误信息
                AssistantMessage finalMsg = response.result().join();
                AgentMessage finalAgentMsg = MessageAdapter.wrap(finalMsg);
                if (addedPartial) {
                    replaceLastMessage(context, finalAgentMsg);
                } else {
                    context.getMessages().add(finalAgentMsg);
                    stream.push(new AgentEvent.MessageStart(finalAgentMsg));
                }
                stream.push(new AgentEvent.MessageEnd(finalAgentMsg));
                return finalAgentMsg;

            } else {
                // 处理 Delta 事件：验证需求 17.7
                // 所有增量事件（TextStart/TextDelta/TextEnd, Thinking* 等）
                // 更新上下文中的最后一条消息，并发射 MessageUpdate 事件
                if (partialAgentMsg != null) {
                    AssistantMessage partial = extractPartialFromEvent(event);
                    if (partial != null) {
                        partialAgentMsg = MessageAdapter.wrap(partial);
                        replaceLastMessage(context, partialAgentMsg);
                    }
                    stream.push(new AgentEvent.MessageUpdate(partialAgentMsg, event));
                }
            }
        }

        // 防御性处理：事件流在没有 Done/Error 事件的情况下结束
        // 这种情况理论上不应发生，但作为防御性编程，确保仍能返回最终消息
        AssistantMessage finalMsg = response.result().join();
        AgentMessage finalAgentMsg = MessageAdapter.wrap(finalMsg);
        if (addedPartial) {
            replaceLastMessage(context, finalAgentMsg);
        } else {
            context.getMessages().add(finalAgentMsg);
            stream.push(new AgentEvent.MessageStart(finalAgentMsg));
        }
        stream.push(new AgentEvent.MessageEnd(finalAgentMsg));
        return finalAgentMsg;
    }

    /**
     * 替换上下文消息列表中的最后一条消息。
     *
     * <p>在流式处理过程中，LLM 会先发送一个"部分消息"（partial message），
     * 然后随着生成过程的推进，不断发送增量更新（delta events）。
     * 每次收到增量更新后，需要将上下文中的部分消息替换为更新后的版本。
     * 最终收到 Done 事件后，再将部分消息替换为完整的最终消息。
     *
     * <p>如果消息列表为空，则不做任何操作（防御性检查）。
     *
     * @param context Agent 上下文，其消息列表将被修改
     * @param message 要替换的新消息
     */
    private static void replaceLastMessage(AgentContext context, AgentMessage message) {
        List<AgentMessage> messages = context.getMessages();
        if (!messages.isEmpty()) {
            messages.set(messages.size() - 1, message);
        }
    }

    /**
     * 从增量事件（Delta Event）中提取部分 {@link AssistantMessage}。
     *
     * <p>LLM 流式响应中的增量事件类型包括：
     * <ul>
     *   <li>文本相关：TextStart → TextDelta → TextEnd（文本内容开始生成、增量更新、结束）</li>
     *   <li>思考相关：ThinkingStart → ThinkingDelta → ThinkingEnd（思考过程开始、增量更新、结束）</li>
     *   <li>工具调用相关：ToolCallStart → ToolCallDelta → ToolCallEnd（工具调用开始、增量更新、结束）</li>
     * </ul>
     *
     * <p>每种增量事件都携带了当前最新的部分消息快照（partial），
     * 该方法从中提取出该快照，用于更新上下文中的消息状态。
     * 如果事件类型不属于上述任何一种增量事件，则返回 {@code null}。
     *
     * <p>使用 if-else instanceof 链式判断，兼容 Java 17 的语法特性（Pattern Matching for instanceof）。
     *
     * @param event 要提取的部分消息事件
     * @return 部分 AssistantMessage，如果事件不是可识别的增量事件则返回 null
     */
    private static AssistantMessage extractPartialFromEvent(AssistantMessageEvent event) {
        if (event instanceof AssistantMessageEvent.TextStart e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.TextDelta e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.TextEnd e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ThinkingStart e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ThinkingDelta e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ThinkingEnd e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ToolCallStart e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ToolCallDelta e) {
            return e.partial();
        } else if (event instanceof AssistantMessageEvent.ToolCallEnd e) {
            return e.partial();
        }
        return null;
    }

    /**
     * 执行助理消息中的工具调用。
     *
     * <p>从助理消息中提取所有工具调用（{@link ToolCall}），
     * 根据配置的执行模式（{@link ToolExecutionMode}）分发到不同的执行路径：
     * <ul>
     *   <li>{@link ToolExecutionMode#SEQUENTIAL} — 顺序执行：每个工具调用依次执行，
     *       前一个完成后才执行下一个（参见 {@link #executeToolCallsSequential}）；</li>
     *   <li>{@link ToolExecutionMode#PARALLEL}（默认）— 并行执行：所有工具调用同时执行，
     *       显著提升执行效率（参见 {@link #executeToolCallsParallel}）。</li>
     * </ul>
     *
     * <p>如果助理消息中没有工具调用，则直接返回空列表。
     *
     * <p><b>验证需求：18.1, 18.2, 18.3, 18.4, 19.1, 19.2, 19.3, 19.4, 19.5</b>
     *
     * @param context         Agent 上下文
     * @param assistantMessage 包含工具调用的助理消息
     * @param config           Agent 循环配置（包含工具执行模式设置）
     * @param signal           可选的取消信号
     * @param stream           事件流，用于发射工具执行事件
     * @return 工具执行结果消息列表，每个结果对应一个工具调用
     */
    static List<ToolResultMessage> executeToolCalls(
            AgentContext context,
            AgentMessage assistantMessage,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream) {

        // 从助理消息中提取工具调用列表
        List<ToolCall> toolCalls = extractToolCalls(assistantMessage);
        if (toolCalls.isEmpty()) {
            return List.of();
        }

        // 根据配置的执行模式分发执行路径（默认使用 PARALLEL 并行模式）
        ToolExecutionMode mode = config.getToolExecution();
        if (mode == ToolExecutionMode.SEQUENTIAL) {
            return executeToolCallsSequential(context, assistantMessage, toolCalls, config, signal, stream);
        } else {
            return executeToolCallsParallel(context, assistantMessage, toolCalls, config, signal, stream);
        }
    }

    /**
     * 顺序执行工具调用：为每个工具调用依次执行 准备 → 执行 → 完成 三个阶段。
     *
     * <p>对于每个工具调用，处理流程如下：
     * <ol>
     *   <li>发射 {@code tool_execution_start} 事件，通知监听器工具开始执行（验证需求 18.1）；</li>
     *   <li>调用 {@link #prepareToolCall} 进行工具准备，包括查找工具、验证参数、调用前置钩子（验证需求 18.2）；</li>
     *   <li>如果准备结果是 {@link PrepareResult.Immediate}（工具未找到、参数校验失败或被阻止）：
     *       直接发射 ToolExecutionEnd 事件，并构建工具结果消息返回，不需要执行（验证需求 18.3）；</li>
     *   <li>如果准备结果是 {@link PrepareResult.Prepared}（所有检查通过）：
     *       调用 {@link #executePreparedToolCall} 执行工具，然后调用 {@link #finalizeExecutedToolCall}
     *       完成后处理（调用后置钩子、发射事件等）（验证需求 18.4）。</li>
     * </ol>
     *
     * <p>顺序执行模式适用于对工具执行顺序有严格要求的场景，例如：
     * <ul>
     *   <li>后一个工具的执行依赖前一个工具的输出结果；</li>
     *   <li>需要确保工具的副作用按特定顺序执行；</li>
     *   <li>调试或日志记录时希望看到清晰的执行顺序。</li>
     * </ul>
     *
     * <p><b>验证需求：18.1, 18.2, 18.3, 18.4</b>
     *
     * @param context         Agent 上下文
     * @param assistantMessage 包含工具调用的助理消息
     * @param toolCalls       要执行的工具调用列表
     * @param config           Agent 循环配置
     * @param signal           可选的取消信号
     * @param stream           事件流
     * @return 工具执行结果消息列表，按工具调用的原始顺序排列
     */
    static List<ToolResultMessage> executeToolCallsSequential(
            AgentContext context,
            AgentMessage assistantMessage,
            List<ToolCall> toolCalls,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream) {

        List<ToolResultMessage> results = new ArrayList<>();

        for (ToolCall toolCall : toolCalls) {
            // 发射 tool_execution_start 事件 —— 通知监听器工具开始执行（验证需求 18.1）
            stream.push(new AgentEvent.ToolExecutionStart(
                    toolCall.id(), toolCall.name(), toolCall.arguments()));

            // 准备工具调用：查找工具、验证参数、调用前置钩子（验证需求 18.2）
            PrepareResult preparation = prepareToolCall(context, assistantMessage, toolCall, config, signal);

            if (preparation instanceof PrepareResult.Immediate immediate) {
                // Immediate 结果：工具未找到、参数校验失败或被阻止（验证需求 18.3）
                // 直接发射 ToolExecutionEnd 事件，构建结果消息，无需执行
                stream.push(new AgentEvent.ToolExecutionEnd(
                        toolCall.id(), toolCall.name(), immediate.result(), immediate.isError()));

                ToolResultMessage toolResultMsg = buildToolResultMessage(
                        toolCall.id(), toolCall.name(), immediate.result(), immediate.isError());
                stream.push(new AgentEvent.MessageStart(MessageAdapter.wrap(toolResultMsg)));
                stream.push(new AgentEvent.MessageEnd(MessageAdapter.wrap(toolResultMsg)));
                results.add(toolResultMsg);

            } else if (preparation instanceof PrepareResult.Prepared prepared) {
                // Prepared 结果：所有检查通过，执行并完成后处理（验证需求 18.4）
                ExecuteResult executed = executePreparedToolCall(prepared, signal, stream);
                ToolResultMessage toolResultMsg = finalizeExecutedToolCall(
                        context, assistantMessage, prepared, executed, config, signal, stream);
                results.add(toolResultMsg);
            }
        }

        return results;
    }

    /**
     * 并行执行工具调用 —— 三阶段流水线架构。
     *
     * <p>采用"顺序准备 → 并行执行 → 顺序完成"的三阶段流水线设计，
     * 在保证准备阶段和完成阶段的可控性的同时，最大化执行阶段的并发效率：
     *
     * <h3>第一阶段：顺序准备（Phase 1: Sequential Prepare）</h3>
     * <p>依次遍历每个工具调用，发射 {@code tool_execution_start} 事件，
     * 然后调用 {@link #prepareToolCall} 进行准备检查。
     * 准备阶段必须顺序执行，因为每个工具的准备可能涉及共享资源的检查
     * （如权限校验、配额检查等），并行准备可能引发竞态条件。
     * 验证需求：19.1。
     *
     * <h3>第二阶段：并行执行（Phase 2: Parallel Execute）</h3>
     * <p>将所有通过准备检查的 {@link PrepareResult.Prepared} 工具
     * 使用 {@link CompletableFuture#supplyAsync} 提交到公共线程池并发执行。
     * 每个工具在自己的线程中独立运行，互不干扰，充分利用多核 CPU 资源。
     * 验证需求：19.2, 19.3。
     *
     * <h3>第三阶段：顺序完成（Phase 3: Sequential Finalize）</h3>
     * <p>按工具调用的原始顺序逐一 join 每个 CompletableFuture 获取执行结果，
     * 然后调用 {@link #finalizeExecutedToolCall} 完成后处理。
     * 保持原始顺序对于结果的一致性很重要，因为 LLM 期望工具结果按调用顺序返回。
     * 验证需求：19.4, 19.5。
     *
     * <p>对于 {@link PrepareResult.Immediate} 的立即返回结果，在第三阶段中
     * 直接发射事件并构建结果消息，无需经过执行阶段。
     *
     * <p><b>注意</b>：并行执行依赖于 {@code ForkJoinPool.commonPool()}，
     * 如果工具执行涉及阻塞操作（如网络 IO），建议在工具内部自行管理线程池。
     *
     * <p><b>验证需求：19.1, 19.2, 19.3, 19.4, 19.5</b>
     *
     * @param context         Agent 上下文
     * @param assistantMessage 包含工具调用的助理消息
     * @param toolCalls       要执行的工具调用列表
     * @param config           Agent 循环配置
     * @param signal           可选的取消信号
     * @param stream           事件流
     * @return 工具执行结果消息列表，按工具调用的原始顺序排列
     */
    static List<ToolResultMessage> executeToolCallsParallel(
            AgentContext context,
            AgentMessage assistantMessage,
            List<ToolCall> toolCalls,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream) {

        // 第一阶段：顺序准备（验证需求 19.1）
        // 依次遍历每个工具调用，进行准备检查。准备阶段必须顺序执行，以避免竞态条件。
        List<PrepareResultEntry> preparedCalls = new ArrayList<>();
        for (ToolCall toolCall : toolCalls) {
            // 发射 tool_execution_start 事件
            stream.push(new AgentEvent.ToolExecutionStart(
                    toolCall.id(), toolCall.name(), toolCall.arguments()));

            // 准备工具调用：查找工具、验证参数、调用前置钩子
            PrepareResult preparation = prepareToolCall(context, assistantMessage, toolCall, config, signal);
            preparedCalls.add(new PrepareResultEntry(toolCall, preparation));
        }

        // 第二阶段：并行执行（验证需求 19.2, 19.3）
        // 将所有通过准备检查的工具提交到公共线程池并发执行
        List<RunningCallEntry> runningCalls = new ArrayList<>();
        for (PrepareResultEntry entry : preparedCalls) {
            if (entry.preparation() instanceof PrepareResult.Prepared prepared) {
                CompletableFuture<ExecuteResult> future = CompletableFuture.supplyAsync(() ->
                        executePreparedToolCall(prepared, signal, stream));
                runningCalls.add(new RunningCallEntry(entry.toolCall(), prepared, future));
            }
        }

        // 第三阶段：按原始顺序完成（验证需求 19.4, 19.5）
        // 按工具调用的原始顺序逐一 join 获取结果，保持 LLM 期望的返回顺序
        List<ToolResultMessage> results = new ArrayList<>();
        int runningIndex = 0;

        for (PrepareResultEntry entry : preparedCalls) {
            if (entry.preparation() instanceof PrepareResult.Immediate immediate) {
                // 处理 Immediate 结果：直接发射事件并构建结果消息
                stream.push(new AgentEvent.ToolExecutionEnd(
                        entry.toolCall().id(), entry.toolCall().name(),
                        immediate.result(), immediate.isError()));

                ToolResultMessage toolResultMsg = buildToolResultMessage(
                        entry.toolCall().id(), entry.toolCall().name(),
                        immediate.result(), immediate.isError());
                stream.push(new AgentEvent.MessageStart(MessageAdapter.wrap(toolResultMsg)));
                stream.push(new AgentEvent.MessageEnd(MessageAdapter.wrap(toolResultMsg)));
                results.add(toolResultMsg);

            } else if (entry.preparation() instanceof PrepareResult.Prepared) {
                // 找到对应的正在执行的任务并 join 等待结果
                RunningCallEntry running = runningCalls.get(runningIndex++);
                ExecuteResult executed = running.future().join();
                ToolResultMessage toolResultMsg = finalizeExecutedToolCall(
                        context, assistantMessage, running.prepared(), executed, config, signal, stream);
                results.add(toolResultMsg);
            }
        }

        return results;
    }

    /**
     * 用于跟踪准备结果及其原始工具调用的内部记录。
     */
    private record PrepareResultEntry(ToolCall toolCall, PrepareResult preparation) {}

    /**
     * 用于跟踪正在执行的工具调用的内部记录。
     * <p>在并行执行场景中，每个准备通过的 {@link PrepareResult.Prepared} 工具
     * 会被提交到线程池异步执行，返回一个 {@link CompletableFuture}。
     * 该记录将工具调用、准备信息和异步 Future 关联起来，
     * 以便在完成阶段按原始顺序 join 并处理结果。
     */
    private record RunningCallEntry(ToolCall toolCall, PrepareResult.Prepared prepared,
                                    CompletableFuture<ExecuteResult> future) {}

    /**
     * 完成已执行的工具调用的后处理 —— 应用后置钩子（AfterToolCallHook）并发射事件。
     *
     * <p>工具执行完成后，需要经过以下步骤才能将结果回传给 LLM：
     * <ol>
     *   <li><b>调用后置钩子（可选）</b>：如果配置了 {@link AfterToolCallHook}，
     *       则在工具执行后调用它，允许业务方对结果进行修改或增强。
     *       后置钩子采用<b>字段级合并</b>策略：只覆盖返回的非空字段，不修改的字段保持原样
     *       （验证需求 22.1, 22.2）；</li>
     *   <li><b>发射 {@code tool_execution_end}</b>：通知监听器工具执行结束（验证需求 22.3）；</li>
     *   <li><b>构建 {@link ToolResultMessage}</b>：将工具结果封装为 LLM 可识别的消息格式
     *       （验证需求 22.4）；</li>
     *   <li><b>发射 {@code message_start} 和 {@code message_end}</b>：将工具结果消息
     *       作为标准消息事件发射（验证需求 22.5）。</li>
     * </ol>
     *
     * <p><b>验证需求：22.1, 22.2, 22.3, 22.4, 22.5</b>
     *
     * @param context     Agent 上下文
     * @param assistantMsg 触发工具调用的助理消息
     * @param prepared    准备后的工具调用信息
     * @param executed    执行结果
     * @param config      Agent 循环配置
     * @param signal      可选的取消信号
     * @param stream      事件流
     * @return 构建完成的工具结果消息
     */
    static ToolResultMessage finalizeExecutedToolCall(
            AgentContext context,
            AgentMessage assistantMsg,
            PrepareResult.Prepared prepared,
            ExecuteResult executed,
            AgentLoopConfig config,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream) {

        AgentToolResult<?> result = executed.result();
        boolean isError = executed.isError();

        // 调用后置钩子（如果配置了）—— 验证需求 22.1, 22.2
        // 后置钩子允许业务方在工具执行完成后对结果进行修改或增强
        AfterToolCallHook afterHook = config.getAfterToolCall();
        if (afterHook != null) {
            // 通过 MessageAdapter 解包获取原始的 AssistantMessage
            AssistantMessage assistantMessage = null;
            if (assistantMsg instanceof MessageAdapter adapter
                    && adapter.message() instanceof AssistantMessage am) {
                assistantMessage = am;
            }

            if (assistantMessage != null) {
                AfterToolCallContext afterContext = new AfterToolCallContext(
                        assistantMessage,
                        prepared.toolCall(),
                        prepared.args(),
                        result,
                        isError,
                        context);

                try {
                    AfterToolCallResult afterResult = afterHook.call(afterContext, signal).join();

                    // 字段级合并：只覆盖非空字段（验证需求 22.2）
                    // 这意味着后置钩子可以只修改想要修改的部分，其他部分保持原样
                    if (afterResult != null) {
                        if (afterResult.content() != null) {
                            result = new AgentToolResult<>(afterResult.content(), result.details());
                        }
                        if (afterResult.details() != null) {
                            result = new AgentToolResult<>(result.content(), afterResult.details());
                        }
                        if (afterResult.isError() != null) {
                            isError = afterResult.isError();
                        }
                    }
                } catch (Exception e) {
                    // 后置钩子执行失败：保留原始结果，不中断流程
                }
            }
        }

        // 发射 tool_execution_end 事件（验证需求 22.3）
        stream.push(new AgentEvent.ToolExecutionEnd(
                prepared.toolCall().id(), prepared.tool().name(), result, isError));

        // 构建 ToolResultMessage（验证需求 22.4）
        ToolResultMessage toolResultMessage = buildToolResultMessage(
                prepared.toolCall().id(), prepared.tool().name(), result, isError);

        // 发射 message_start/message_end 事件（验证需求 22.5）
        AgentMessage wrappedResult = MessageAdapter.wrap(toolResultMessage);
        stream.push(new AgentEvent.MessageStart(wrappedResult));
        stream.push(new AgentEvent.MessageEnd(wrappedResult));

        return toolResultMessage;
    }

    /**
     * 从工具执行结果构建 {@link ToolResultMessage}。
     *
     * <p>将工具执行的原始结果（{@link AgentToolResult}）封装为 LLM 可识别的
     * {@link ToolResultMessage} 格式。该消息将被追加到上下文消息列表中，
     * 作为工具调用的响应，供 LLM 在下一轮生成中使用。
     *
     * <p>构建时包含以下字段：
     * <ul>
     *   <li>toolCallId — 工具调用 ID，用于关联 LLM 的工具调用请求和结果；</li>
     *   <li>toolName — 工具名称，用于标识调用的是哪个工具；</li>
     *   <li>content — 工具执行的内容结果（如文本输出、数据等）；</li>
     *   <li>details — 附加的详细信息（如执行时间、元数据等）；</li>
     *   <li>isError — 是否发生错误；</li>
     *   <li>timestamp — 当前时间戳，记录结果产生的时间。</li>
     * </ul>
     *
     * @param toolCallId 工具调用 ID
     * @param toolName   工具名称
     * @param result     工具执行结果
     * @param isError    是否发生错误
     * @return 构建完成的工具结果消息
     */
    private static ToolResultMessage buildToolResultMessage(
            String toolCallId,
            String toolName,
            AgentToolResult<?> result,
            boolean isError) {
        return new ToolResultMessage(
                toolCallId,
                toolName,
                result.content(),
                result.details(),
                isError,
                System.currentTimeMillis());
    }

    // ── 工具准备 ─────────────────────────────────────────────────

    /**
     * 准备工具调用 —— 执行工具执行前的所有检查。
     *
     * <p>在工具正式执行之前，需要进行一系列检查和验证，确保工具调用是合法且安全的：
     * <ol>
     *   <li><b>查找工具</b>：根据工具调用名称（{@code toolCall.name()}）在上下文的
     *       工具列表中查找匹配的 {@link AgentTool}。如果找不到，返回 Immediate 错误结果
     *       （验证需求 20.1, 20.2）；</li>
     *   <li><b>参数校验</b>：使用 {@link ToolValidator#validateToolArguments} 校验工具调用
     *       的参数是否符合 JSON Schema 定义。如果校验失败，返回 Immediate 错误结果
     *       （验证需求 20.3, 20.4）；</li>
     *   <li><b>前置钩子检查</b>：如果配置了 {@link BeforeToolCallHook}，则调用它以允许
     *       业务方在工具执行前进行拦截、审批或修改参数。如果前置钩子返回了阻止（block）结果，
     *       则返回 Immediate 错误结果（验证需求 20.5, 20.6）。</li>
     * </ol>
     *
     * <p>如果所有检查都通过，返回 {@link PrepareResult.Prepared}，其中包含原始工具调用、
     * 解析后的 AgentTool 和校验通过后的参数（验证需求 20.7）。
     *
     * <p><b>验证需求：20.1, 20.2, 20.3, 20.4, 20.5, 20.6, 20.7</b>
     *
     * @param context      Agent 上下文，从中查找工具
     * @param assistantMsg 触发工具调用的助理消息，用于提取 AssistantMessage 供前置钩子使用
     * @param toolCall     要准备的工具调用
     * @param config       Agent 循环配置，包含前置钩子等配置项
     * @param signal       可选的取消信号
     * @return {@link PrepareResult} 实例：
     *         <ul>
     *           <li>{@link PrepareResult.Prepared} — 所有检查通过，工具可以执行；</li>
     *           <li>{@link PrepareResult.Immediate} — 检查失败，包含错误结果。</li>
     *         </ul>
     */
    static PrepareResult prepareToolCall(
            AgentContext context,
            AgentMessage assistantMsg,
            ToolCall toolCall,
            AgentLoopConfig config,
            CancellationSignal signal) {

        // 1. 查找工具：根据工具调用名称在上下文中匹配对应的 AgentTool（验证需求 20.1, 20.2）
        AgentTool tool = null;
        if (context.getTools() != null) {
            for (AgentTool t : context.getTools()) {
                if (t.name().equals(toolCall.name())) {
                    tool = t;
                    break;
                }
            }
        }
        if (tool == null) {
            // 工具未找到，返回 Immediate 错误结果
            return new PrepareResult.Immediate(
                    createErrorToolResult("Tool " + toolCall.name() + " not found"), true);
        }

        // 2. 参数校验：使用 ToolValidator 校验工具调用参数是否符合 JSON Schema（验证需求 20.3, 20.4）
        try {
            ToolValidator.validateToolArguments(tool.toTool(), toolCall);
        } catch (IllegalArgumentException e) {
            // 参数校验失败，返回 Immediate 错误结果
            return new PrepareResult.Immediate(
                    createErrorToolResult(e.getMessage()), true);
        }

        // 3. 调用前置钩子（如果配置了）—— 验证需求 20.5, 20.6
        // 前置钩子允许业务方在工具执行前进行拦截、审批或修改参数
        if (config.getBeforeToolCall() != null) {
            // 通过 MessageAdapter 解包获取原始的 AssistantMessage
            AssistantMessage assistantMessage = null;
            if (assistantMsg instanceof MessageAdapter adapter
                    && adapter.message() instanceof AssistantMessage am) {
                assistantMessage = am;
            }

            if (assistantMessage != null) {
                BeforeToolCallContext beforeContext = new BeforeToolCallContext(
                        assistantMessage, toolCall, toolCall.arguments(), context);
                try {
                    BeforeToolCallResult beforeResult =
                            config.getBeforeToolCall().call(beforeContext, signal).join();
                    // 如果前置钩子返回了阻止（block）结果，则返回 Immediate 错误结果
                    if (beforeResult != null && Boolean.TRUE.equals(beforeResult.block())) {
                        String reason = beforeResult.reason() != null
                                ? beforeResult.reason()
                                : "Tool execution was blocked";
                        return new PrepareResult.Immediate(
                                createErrorToolResult(reason), true);
                    }
                } catch (Exception e) {
                    // 前置钩子执行失败，返回 Immediate 错误结果
                    return new PrepareResult.Immediate(
                            createErrorToolResult("BeforeToolCallHook failed: " + e.getMessage()), true);
                }
            }
        }

        // 4. 所有检查通过，返回 Prepared 状态（验证需求 20.7）
        return new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
    }

    // ── 工具执行 ──────────────────────────────────────────────────

    /**
     * 执行已准备好的工具调用 —— 调用工具的 {@code execute} 方法。
     *
     * <p>该方法是工具执行阶段的核心，负责将准备阶段通过的 {@link PrepareResult.Prepared}
     * 转化为实际的工具执行结果。处理流程如下：
     * <ol>
     *   <li>创建 {@code onUpdate} 回调：当工具在执行过程中报告部分结果时，
     *       通过事件流发射 {@link AgentEvent.ToolExecutionUpdate} 事件，
     *       让监听器能够实时了解工具的执行进度（验证需求 21.2）；</li>
     *   <li>调用工具的 {@code execute} 方法：将参数对象转换为 {@link JsonNode} 格式，
     *       调用 {@link AgentTool#execute} 执行实际逻辑，并阻塞等待结果（验证需求 21.1）；</li>
     *   <li>处理结果：成功时返回 {@link ExecuteResult} 并设置 {@code isError=false}；
     *       异常时捕获异常信息，返回错误结果并设置 {@code isError=true}（验证需求 21.3, 21.4）。</li>
     * </ol>
     *
     * <p>异常处理会尝试解包 {@link java.util.concurrent.CompletionException}，
     * 获取根因异常的消息，使错误信息更准确。
     *
     * <p><b>验证需求：21.1, 21.2, 21.3, 21.4</b>
     *
     * @param prepared 已准备好的工具调用（包含原始 toolCall、解析后的 AgentTool、校验通过的参数）
     * @param signal   可选的取消信号，传递给工具执行方法
     * @param stream   事件流，用于发射 tool_execution_update 事件
     * @return {@link ExecuteResult} 包含工具执行结果和错误标志
     */
    static ExecuteResult executePreparedToolCall(
            PrepareResult.Prepared prepared,
            CancellationSignal signal,
            EventStream<AgentEvent, List<AgentMessage>> stream) {

        // 创建 onUpdate 回调：工具执行过程中报告部分结果时，发射 tool_execution_update 事件（验证需求 21.2）
        // 这使得监听器能够实时了解工具的执行进度，如流式读取文件、逐步处理数据等
        AgentToolUpdateCallback onUpdate = (partialResult) -> {
            stream.push(new AgentEvent.ToolExecutionUpdate(
                    prepared.toolCall().id(),
                    prepared.tool().name(),
                    prepared.args(),
                    partialResult));
        };

        try {
            // 将参数对象转换为 JsonNode 格式，供 AgentTool.execute 使用（验证需求 21.1）
            JsonNode argsAsJsonNode = PiAiJson.MAPPER.valueToTree(prepared.args());

            // 调用 AgentTool.execute 并阻塞等待结果（验证需求 21.1）
            // 工具执行是异步的（返回 CompletableFuture），通过 .join() 等待完成
            AgentToolResult<?> result = prepared.tool()
                    .execute(prepared.toolCall().id(), argsAsJsonNode, signal, onUpdate)
                    .join();

            // 验证需求 21.3：执行成功，返回结果和 isError=false
            return new ExecuteResult(result, false);
        } catch (Exception e) {
            // 验证需求 21.4：捕获异常，返回错误结果和 isError=true
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Tool execution failed";
            // 解包 CompletionException 以获取根因异常的消息
            // CompletionException 是 CompletableFuture 在异步执行失败时抛出的包装异常
            Throwable cause = e.getCause();
            if (cause != null && cause.getMessage() != null) {
                errorMessage = cause.getMessage();
            }
            return new ExecuteResult(createErrorToolResult(errorMessage), true);
        }
    }

    /**
     * 创建错误工具结果 —— 包含错误消息的 {@link AgentToolResult}。
     *
     * <p>当工具执行过程中发生错误时，使用此方法创建一个标准化的错误结果。
     * 结果中包含唯一的 {@link TextContent}，内容为错误描述信息，
     * 详情（details）映射为空 Map。
     *
     * <p>该错误结果会被 LLM 接收，LLM 可以根据错误信息调整其行为，
     * 例如重试工具调用、向用户报告错误、或尝试其他替代方案。
     *
     * @param message 错误描述信息
     * @return 包含错误消息的工具结果
     */
    static AgentToolResult<?> createErrorToolResult(String message) {
        return new AgentToolResult<>(
                List.of(new TextContent(message)),
                Map.of()
        );
    }

    // ── 辅助方法 ───────────────────────────────────────────────────

    /**
     * 轮询 Steering 消息 —— 从配置的回调中获取注入的引导消息。
     *
     * <p>Steering 消息机制允许外部系统在 Agent 循环运行过程中注入消息，
     * 用于干预或引导 Agent 的行为。例如：
     * <ul>
     *   <li>用户在 Agent 思考过程中输入了新的指令或问题；</li>
     *   <li>其他系统组件在 Agent 执行工具调用期间发送了优先级消息；</li>
     *   <li>监控系统发现 Agent 偏离了目标，需要注入纠正指令。</li>
     * </ul>
     *
     * <p>该方法在每次内层循环迭代结束时调用，检查是否有新的 Steering 消息到达。
     * 如果有，这些消息将在下一轮 LLM 请求之前被注入到上下文中。
     *
     * <p>如果配置的回调为 null 或返回 null，则返回空列表。
     * 回调中的异常也会被静默捕获，返回空列表，确保不因 Steering 消息问题中断 Agent 循环。
     *
     * @param config Agent 循环配置，包含 getSteeringMessages 回调
     * @return Steering 消息列表，如果没有则返回空列表
     */
    private static List<AgentMessage> pollSteeringMessages(AgentLoopConfig config) {
        if (config.getGetSteeringMessages() == null) {
            return new ArrayList<>();
        }
        try {
            // 调用回调获取 Steering 消息，通过 .join() 阻塞等待 CompletableFuture 完成
            List<AgentMessage> messages = config.getGetSteeringMessages().get().join();
            return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        } catch (Exception e) {
            // 异常静默处理：返回空列表，避免因 Steering 消息问题中断 Agent 循环
            return new ArrayList<>();
        }
    }

    /**
     * 轮询 FollowUp 消息 —— 从配置的回调中获取后续消息。
     *
     * <p>FollowUp 消息机制用于实现"后续追问"场景。当 Agent 即将停止时
     * （内层循环结束，没有更多工具调用和 Steering 消息），该方法被调用
     * 以检查是否有需要继续处理的消息。
     *
     * <p>典型场景：
     * <ul>
     *   <li>Agent 完成了一轮对话后，需要根据预设条件继续追问用户；</li>
     *   <li>外部系统在 Agent 即将停止时推送了新的输入；</li>
     *   <li>多轮对话中的自动跟进逻辑。</li>
     * </ul>
     *
     * <p>与 Steering 消息的区别：Steering 消息在内层循环中注入，
     * 而 FollowUp 消息在外层循环中注入，在 Agent 即将停止时触发。
     *
     * <p>如果配置的回调为 null 或返回 null，则返回空列表。
     * 回调中的异常也会被静默捕获，返回空列表。
     *
     * @param config Agent 循环配置，包含 getFollowUpMessages 回调
     * @return FollowUp 消息列表，如果没有则返回空列表
     */
    private static List<AgentMessage> pollFollowUpMessages(AgentLoopConfig config) {
        if (config.getGetFollowUpMessages() == null) {
            return new ArrayList<>();
        }
        try {
            // 调用回调获取 FollowUp 消息，通过 .join() 阻塞等待 CompletableFuture 完成
            List<AgentMessage> messages = config.getGetFollowUpMessages().get().join();
            return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        } catch (Exception e) {
            // 异常静默处理：返回空列表，避免因 FollowUp 消息问题中断 Agent 循环
            return new ArrayList<>();
        }
    }

    /**
     * 从 {@link AgentMessage} 中提取 {@link StopReason}（LLM 停止原因）。
     *
     * <p>LLM 完成一次响应后，会返回一个停止原因，指示它为什么停止生成。
     * 常见的停止原因包括：
     * <ul>
     *   <li>{@link StopReason#END_TURN} — 正常结束，LLM 完成了当前轮次的内容生成；</li>
     *   <li>{@link StopReason#TOOL_USE} — 触发了工具调用，LLM 期望执行工具后继续；</li>
     *   <li>{@link StopReason#ERROR} — 发生了错误，LLM 无法正常完成响应；</li>
     *   <li>{@link StopReason#ABORTED} — 被外部中断，如取消信号触发或超时。</li>
     * </ul>
     *
     * <p>在 {@link #runLoop} 中，如果提取到的停止原因是 ERROR 或 ABORTED，
     * 会立即终止 Agent 循环，而不是继续执行工具调用或等待更多消息。
     *
     * <p>如果消息不是 {@link AssistantMessage} 的包装（通过 {@link MessageAdapter} 适配），
     * 则返回 {@code null}。
     *
     * @param agentMsg 要提取停止原因的 Agent 消息
     * @return 停止原因，如果消息类型不匹配则返回 null
     */
    private static StopReason extractStopReason(AgentMessage agentMsg) {
        if (agentMsg instanceof MessageAdapter adapter
                && adapter.message() instanceof AssistantMessage assistantMsg) {
            return assistantMsg.getStopReason();
        }
        return null;
    }

    /**
     * 从 {@link AgentMessage} 中提取 {@link ToolCall} 内容块列表。
     *
     * <p>LLM 在响应中可能会请求调用一个或多个工具，这些工具调用以
     * {@link ToolCall} 内容块的形式嵌入在 {@link AssistantMessage} 中。
     * 该方法遍历消息的内容块列表，筛选出所有类型为 {@link ToolCall} 的块。
     *
     * <p>提取到的工具调用列表将用于：
     * <ul>
     *   <li>在 {@link #runLoop} 中判断是否继续内层循环（有工具调用则继续）；</li>
     *   <li>传递给 {@link #executeToolCalls} 执行实际的工具调用。</li>
     * </ul>
     *
     * <p>如果消息不是 {@link AssistantMessage} 的包装，或者消息内容为空，
     * 或者没有工具调用，则返回空列表。
     *
     * @param agentMsg 要提取工具调用的 Agent 消息
     * @return 工具调用列表，如果没有则返回空列表
     */
    private static List<ToolCall> extractToolCalls(AgentMessage agentMsg) {
        if (agentMsg instanceof MessageAdapter adapter
                && adapter.message() instanceof AssistantMessage assistantMsg) {
            if (assistantMsg.getContent() == null) {
                return Collections.emptyList();
            }
            List<ToolCall> toolCalls = new ArrayList<>();
            for (AssistantContentBlock block : assistantMsg.getContent()) {
                if (block instanceof ToolCall tc) {
                    toolCalls.add(tc);
                }
            }
            return toolCalls;
        }
        return Collections.emptyList();
    }
}

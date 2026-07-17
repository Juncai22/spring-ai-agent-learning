package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.BeforeToolCallResult;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.event.EventStream;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.ToolResultMessage;
import com.pi.ai.core.types.Usage;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 6: BeforeToolCall 拦截阻止执行
 *
 * <p>For any tool call, when BeforeToolCallHook returns {@code block=true},
 * the tool's {@code execute} method should not be called, and the Agent loop
 * should generate an error ToolResultMessage containing the block reason
 * (or default message "Tool execution was blocked").
 *
 * <p><b>Validates: Requirements 3.2, 3.3, 20.6</b>
 */
class BeforeToolCallBlockPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<String> blockReasons() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)
        );
    }

    @Provide
    Arbitrary<String> toolNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<Integer> toolCallCounts() {
        return Arbitraries.integers().between(1, 5);
    }

    // ==================== Helpers ====================

    /**
     * Creates a tool that tracks whether execute was called.
     */
    private static AgentTool createTrackingTool(String name, AtomicBoolean executeCalled) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Tracking tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                executeCalled.set(true);
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("executed")), null));
            }
        };
    }

    /**
     * Creates a tool that counts how many times execute was called.
     */
    private static AgentTool createCountingTool(String name, AtomicInteger executeCount) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Counting tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                executeCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("executed")), null));
            }
        };
    }

    private static AgentMessage wrapAssistantMessage(List<ToolCall> toolCalls) {
        List<com.pi.ai.core.types.AssistantContentBlock> content = new ArrayList<>(toolCalls);
        AssistantMessage msg = AssistantMessage.builder()
                .content(content)
                .stopReason(StopReason.TOOL_USE)
                .usage(new Usage(0, 0, 0, 0, 0, null))
                .timestamp(System.currentTimeMillis())
                .build();
        return MessageAdapter.wrap(msg);
    }

    private static AgentContext contextWithTools(List<AgentTool> tools) {
        return AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
    }

    private static EventStream<AgentEvent, List<AgentMessage>> createStream() {
        return AgentLoop.createAgentStream();
    }

    private static List<AgentEvent> collectEvents(EventStream<AgentEvent, List<AgentMessage>> stream) {
        stream.end(List.of());
        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    // ==================== Property Tests ====================

    /**
     * Property 6: BeforeToolCall block prevents tool execution.
     *
     * <p>When BeforeToolCallHook returns block=true, the tool's execute method
     * should not be called.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_preventsToolExecution(
            @ForAll("blockReasons") String blockReason
    ) {
        AtomicBoolean executeCalled = new AtomicBoolean(false);
        String toolName = "blockedTool";
        AgentTool tool = createTrackingTool(toolName, executeCalled);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, blockReason)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        AgentMessage assistantMsg = wrapAssistantMessage(List.of(toolCall));
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        // Execute via prepareToolCall (which checks BeforeToolCallHook)
        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, assistantMsg, toolCall, config, null);

        // Should return Immediate (blocked), not Prepared
        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        
        // Tool execute should NOT have been called
        assertThat(executeCalled.get()).isFalse();
    }

    /**
     * Property 6: BeforeToolCall block generates error ToolResultMessage.
     *
     * <p>When blocked, the result should be an error with isError=true.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_generatesErrorResult(
            @ForAll("blockReasons") String blockReason
    ) {
        AtomicBoolean executeCalled = new AtomicBoolean(false);
        String toolName = "blockedTool";
        AgentTool tool = createTrackingTool(toolName, executeCalled);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, blockReason)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        AgentMessage assistantMsg = wrapAssistantMessage(List.of(toolCall));

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, assistantMsg, toolCall, config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        
        // Should be marked as error
        assertThat(immediate.isError()).isTrue();
    }

    /**
     * Property 6: BeforeToolCall block uses provided reason or default message.
     *
     * <p>When blockReason is provided, it should appear in the error content.
     * When blockReason is null, the default message "Tool execution was blocked" should be used.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_usesProvidedReasonOrDefault(
            @ForAll("blockReasons") String blockReason
    ) {
        AtomicBoolean executeCalled = new AtomicBoolean(false);
        String toolName = "blockedTool";
        AgentTool tool = createTrackingTool(toolName, executeCalled);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, blockReason)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        AgentMessage assistantMsg = wrapAssistantMessage(List.of(toolCall));

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, assistantMsg, toolCall, config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        
        // Check the error message content
        assertThat(immediate.result().content()).hasSize(1);
        TextContent textContent = (TextContent) immediate.result().content().get(0);
        
        if (blockReason != null) {
            assertThat(textContent.text()).isEqualTo(blockReason);
        } else {
            assertThat(textContent.text()).isEqualTo("Tool execution was blocked");
        }
    }

    /**
     * Property 6: BeforeToolCall block in sequential execution.
     *
     * <p>When multiple tools are executed sequentially and one is blocked,
     * only the blocked tool should not execute.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_sequentialExecution_onlyBlockedToolSkipped(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        // Block only the first tool
        AtomicInteger executeCount = new AtomicInteger(0);
        
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createCountingTool(toolName, executeCount));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) -> {
                    // Block only the first tool (tc-0)
                    if ("tc-0".equals(context.toolCall().id())) {
                        return CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, "Blocked first tool"));
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // All tools should have results
        assertThat(results).hasSize(toolCallCount);
        
        // First tool should be error (blocked)
        assertThat(results.get(0).isError()).isTrue();
        
        // Other tools should have executed (not errors)
        for (int i = 1; i < toolCallCount; i++) {
            assertThat(results.get(i).isError()).isFalse();
        }
        
        // Execute count should be toolCallCount - 1 (first tool was blocked)
        assertThat(executeCount.get()).isEqualTo(toolCallCount - 1);
    }

    /**
     * Property 6: BeforeToolCall block in parallel execution.
     *
     * <p>When multiple tools are executed in parallel and one is blocked,
     * only the blocked tool should not execute.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_parallelExecution_onlyBlockedToolSkipped(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        AtomicInteger executeCount = new AtomicInteger(0);
        
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createCountingTool(toolName, executeCount));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) -> {
                    // Block only the first tool (tc-0)
                    if ("tc-0".equals(context.toolCall().id())) {
                        return CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, "Blocked first tool"));
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // All tools should have results
        assertThat(results).hasSize(toolCallCount);
        
        // First tool should be error (blocked)
        assertThat(results.get(0).isError()).isTrue();
        
        // Other tools should have executed (not errors)
        for (int i = 1; i < toolCallCount; i++) {
            assertThat(results.get(i).isError()).isFalse();
        }
        
        // Execute count should be toolCallCount - 1 (first tool was blocked)
        assertThat(executeCount.get()).isEqualTo(toolCallCount - 1);
    }

    /**
     * Property 6: BeforeToolCall block emits tool_execution_end with isError=true.
     *
     * <p>When a tool is blocked, the tool_execution_end event should have isError=true.
     */
    @Property(tries = 100)
    void beforeToolCallBlock_emitsToolExecutionEndWithError(
            @ForAll("blockReasons") String blockReason
    ) {
        AtomicBoolean executeCalled = new AtomicBoolean(false);
        String toolName = "blockedTool";
        AgentTool tool = createTrackingTool(toolName, executeCalled);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, blockReason)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        AgentMessage assistantMsg = wrapAssistantMessage(List.of(toolCall));
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        // Execute via sequential (which handles Immediate results)
        AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, List.of(toolCall), config, null, stream);

        List<AgentEvent> events = collectEvents(stream);

        // Find tool_execution_end event
        List<AgentEvent.ToolExecutionEnd> endEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .toList();

        assertThat(endEvents).hasSize(1);
        assertThat(endEvents.get(0).isError()).isTrue();
        assertThat(endEvents.get(0).toolCallId()).isEqualTo("tc-1");
    }
}

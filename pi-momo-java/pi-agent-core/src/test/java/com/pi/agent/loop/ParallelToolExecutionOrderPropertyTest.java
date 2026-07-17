package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.MessageAdapter;
import com.pi.agent.types.ToolExecutionMode;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 5: 并行工具执行保持源顺序
 *
 * <p>For any assistant message containing N tool calls (N ≥ 2), when ToolExecutionMode
 * is PARALLEL, the final ToolResultMessage list order should match the order of tool calls
 * in the assistant message. That is, {@code results[i].toolCallId == toolCalls[i].id}
 * for all i.
 *
 * <p><b>Validates: Requirements 19.4, 19.5</b>
 */
class ParallelToolExecutionOrderPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> toolCallCounts() {
        return Arbitraries.integers().between(2, 5);
    }

    @Provide
    Arbitrary<String> toolNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    // ==================== Helpers ====================

    /**
     * Creates a tool that executes with a random delay to simulate varying execution times.
     * This helps verify that parallel execution maintains order despite different completion times.
     */
    private static AgentTool createDelayedTool(String name, List<String> executionOrder) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Delayed tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.supplyAsync(() -> {
                    // Random delay between 10-100ms to simulate varying execution times
                    try {
                        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    executionOrder.add(toolCallId);
                    return new AgentToolResult<>(
                            List.of(new TextContent("result for " + toolCallId)), null);
                });
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

    // ==================== Property Tests ====================

    /**
     * Property 5: Parallel tool execution preserves source order.
     *
     * <p>Generates 2-5 random tool calls, executes them in PARALLEL mode,
     * and verifies that results[i].toolCallId == toolCalls[i].id for all i.
     */
    @Property(tries = 100)
    void parallelToolExecution_preservesSourceOrder(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        // Track actual execution order (may differ from source order due to parallel execution)
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        // Create tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createDelayedTool(toolName, executionOrder));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        // Execute tool calls in parallel
        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Verify results are in source order
        assertThat(results).hasSize(toolCallCount);
        for (int i = 0; i < toolCallCount; i++) {
            assertThat(results.get(i).toolCallId())
                    .as("Result at index %d should have toolCallId %s", i, toolCalls.get(i).id())
                    .isEqualTo(toolCalls.get(i).id());
        }
    }

    /**
     * Property 5: Parallel tool execution - results match tool calls by ID.
     *
     * <p>Verifies that each result's toolCallId matches the corresponding tool call's id.
     */
    @Property(tries = 100)
    void parallelToolExecution_resultsMatchToolCallsById(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createDelayedTool(toolName, executionOrder));
            toolCalls.add(new ToolCall("unique-id-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Verify each result's toolCallId matches the corresponding tool call
        for (int i = 0; i < toolCallCount; i++) {
            ToolCall expectedToolCall = toolCalls.get(i);
            ToolResultMessage actualResult = results.get(i);
            
            assertThat(actualResult.toolCallId())
                    .isEqualTo(expectedToolCall.id());
            assertThat(actualResult.toolName())
                    .isEqualTo(expectedToolCall.name());
        }
    }

    /**
     * Property 5: Parallel tool execution - order preserved even with varying delays.
     *
     * <p>Uses tools with significantly different execution times to verify
     * that the result order is based on source order, not completion order.
     */
    @Property(tries = 100)
    void parallelToolExecution_orderPreservedDespiteVaryingDelays(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<String> completionOrder = new CopyOnWriteArrayList<>();

        // Create tools with intentionally varying delays
        // First tool is slowest, last tool is fastest
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            final int delay = (toolCallCount - i) * 20; // Reverse delay: first tool slowest
            
            AgentTool tool = new AgentTool() {
                @Override public String name() { return toolName; }
                @Override public String description() { return "Tool with delay " + delay; }
                @Override public JsonNode parameters() { return null; }
                @Override public CompletableFuture<AgentToolResult<?>> execute(
                        String toolCallId, JsonNode args, CancellationSignal signal,
                        AgentToolUpdateCallback onUpdate) {
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        completionOrder.add(toolCallId);
                        return new AgentToolResult<>(
                                List.of(new TextContent("result")), null);
                    });
                }
            };
            
            tools.add(tool);
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Results should be in source order (tc-0, tc-1, tc-2, ...)
        // even though completion order is likely reversed (tc-2, tc-1, tc-0, ...)
        assertThat(results).hasSize(toolCallCount);
        for (int i = 0; i < toolCallCount; i++) {
            assertThat(results.get(i).toolCallId()).isEqualTo("tc-" + i);
        }
    }

    /**
     * Property 5: Parallel tool execution - tool_execution_end events in source order.
     *
     * <p>Verifies that tool_execution_end events are emitted in source order
     * during the finalize phase.
     */
    @Property(tries = 100)
    void parallelToolExecution_toolExecutionEndEventsInSourceOrder(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createDelayedTool(toolName, executionOrder));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = contextWithTools(tools);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCallsParallel(ctx, assistantMsg, toolCalls, config, null, stream);

        // Collect events
        stream.end(List.of());
        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }

        // Extract tool_execution_end events
        List<AgentEvent.ToolExecutionEnd> endEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .toList();

        // Verify tool_execution_end events are in source order
        assertThat(endEvents).hasSize(toolCallCount);
        for (int i = 0; i < toolCallCount; i++) {
            assertThat(endEvents.get(i).toolCallId()).isEqualTo("tc-" + i);
        }
    }
}

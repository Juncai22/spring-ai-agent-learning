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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for sequential and parallel tool execution modes.
 *
 * <p><b>Validates: Requirements 18.1, 18.2, 18.3, 18.4, 19.1, 19.2, 19.3, 19.4, 19.5</b>
 */
class ToolExecutionModeTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static AgentTool createTool(String name, AgentToolResult<?> result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Test tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.completedFuture(result);
            }
        };
    }

    private static AgentTool createDelayedTool(String name, AgentToolResult<?> result, 
                                                long delayMs, List<String> executionOrder) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Delayed tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    executionOrder.add(name);
                    return result;
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

    private static ToolCall toolCall(String id, String name) {
        return new ToolCall(id, name, Map.of());
    }

    private static AgentContext contextWithTools(AgentTool... tools) {
        return AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tools))
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

    // ── Sequential execution (Req 18.1, 18.2, 18.3, 18.4) ────────────────

    @Test
    void executeToolCallsSequential_executesInOrder() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        
        AgentTool tool1 = createDelayedTool("tool1", result, 50, executionOrder);
        AgentTool tool2 = createDelayedTool("tool2", result, 10, executionOrder);
        AgentTool tool3 = createDelayedTool("tool3", result, 30, executionOrder);
        
        AgentContext ctx = contextWithTools(tool1, tool2, tool3);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"),
                toolCall("tc-3", "tool3"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Sequential: should execute in order regardless of delay
        assertThat(executionOrder).containsExactly("tool1", "tool2", "tool3");
        assertThat(results).hasSize(3);
    }

    @Test
    void executeToolCallsSequential_emitsToolExecutionStartForEach() {
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        AgentTool tool1 = createTool("tool1", result);
        AgentTool tool2 = createTool("tool2", result);
        
        AgentContext ctx = contextWithTools(tool1, tool2);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCallsSequential(ctx, assistantMsg, toolCalls, config, null, stream);

        List<AgentEvent> events = collectEvents(stream);
        List<AgentEvent.ToolExecutionStart> startEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionStart)
                .map(e -> (AgentEvent.ToolExecutionStart) e)
                .toList();

        assertThat(startEvents).hasSize(2);
        assertThat(startEvents.get(0).toolCallId()).isEqualTo("tc-1");
        assertThat(startEvents.get(1).toolCallId()).isEqualTo("tc-2");
    }

    @Test
    void executeToolCallsSequential_immediateResult_emitsEventsWithoutExecution() {
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        AgentTool tool1 = createTool("tool1", result);
        
        AgentContext ctx = contextWithTools(tool1);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();
        
        // nonexistent tool should produce Immediate result
        List<ToolCall> toolCalls = List.of(toolCall("tc-1", "nonexistent"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, toolCalls, config, null, stream);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isError()).isTrue();

        List<AgentEvent> events = collectEvents(stream);
        List<AgentEvent.ToolExecutionEnd> endEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .toList();
        assertThat(endEvents).hasSize(1);
        assertThat(endEvents.get(0).isError()).isTrue();
    }

    @Test
    void executeToolCallsSequential_returnsResultsInOrder() {
        AgentToolResult<?> result1 = new AgentToolResult<>(List.of(new TextContent("result1")), null);
        AgentToolResult<?> result2 = new AgentToolResult<>(List.of(new TextContent("result2")), null);
        AgentTool tool1 = createTool("tool1", result1);
        AgentTool tool2 = createTool("tool2", result2);
        
        AgentContext ctx = contextWithTools(tool1, tool2);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, toolCalls, config, null, stream);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).toolCallId()).isEqualTo("tc-1");
        assertThat(results.get(1).toolCallId()).isEqualTo("tc-2");
    }

    // ── Parallel execution (Req 19.1, 19.2, 19.3, 19.4, 19.5) ────────────

    @Test
    void executeToolCallsParallel_executesConcurrently() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(3);
        CountDownLatch endLatch = new CountDownLatch(1);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        
        AgentTool concurrentTool = new AgentTool() {
            @Override public String name() { return "concurrent"; }
            @Override public String description() { return "Concurrent tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.supplyAsync(() -> {
                    int current = concurrentCount.incrementAndGet();
                    maxConcurrent.updateAndGet(max -> Math.max(max, current));
                    startLatch.countDown();
                    try {
                        endLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    concurrentCount.decrementAndGet();
                    return new AgentToolResult<>(List.of(new TextContent("done")), null);
                });
            }
        };
        
        AgentContext ctx = contextWithTools(concurrentTool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "concurrent"),
                toolCall("tc-2", "concurrent"),
                toolCall("tc-3", "concurrent"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        // Start parallel execution in a separate thread
        CompletableFuture<List<ToolResultMessage>> future = CompletableFuture.supplyAsync(() ->
                AgentLoop.executeToolCallsParallel(ctx, assistantMsg, toolCalls, config, null, stream));

        // Wait for all tools to start executing
        boolean allStarted = startLatch.await(5, TimeUnit.SECONDS);
        assertThat(allStarted).isTrue();
        
        // At this point, all 3 should be running concurrently
        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2); // At least 2 concurrent
        
        // Release all tools to complete
        endLatch.countDown();
        
        List<ToolResultMessage> results = future.join();
        assertThat(results).hasSize(3);
    }

    @Test
    void executeToolCallsParallel_preparesSequentially() {
        List<String> prepareOrder = new CopyOnWriteArrayList<>();
        
        AgentTool trackingTool = new AgentTool() {
            @Override public String name() { return "tracking"; }
            @Override public String description() { return "Tracking tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("done")), null));
            }
        };
        
        AgentContext ctx = contextWithTools(trackingTool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .beforeToolCall((context, signal) -> {
                    prepareOrder.add(context.toolCall().id());
                    return CompletableFuture.completedFuture(null);
                })
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tracking"),
                toolCall("tc-2", "tracking"),
                toolCall("tc-3", "tracking"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCallsParallel(ctx, assistantMsg, toolCalls, config, null, stream);

        // Prepare should happen in order
        assertThat(prepareOrder).containsExactly("tc-1", "tc-2", "tc-3");
    }

    @Test
    void executeToolCallsParallel_finalizesInOriginalOrder() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        
        // tool1 is slow, tool2 is fast, tool3 is medium
        AgentTool tool1 = createDelayedTool("tool1", result, 100, executionOrder);
        AgentTool tool2 = createDelayedTool("tool2", result, 10, executionOrder);
        AgentTool tool3 = createDelayedTool("tool3", result, 50, executionOrder);
        
        AgentContext ctx = contextWithTools(tool1, tool2, tool3);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"),
                toolCall("tc-3", "tool3"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Results should be in original order regardless of execution completion order
        assertThat(results).hasSize(3);
        assertThat(results.get(0).toolCallId()).isEqualTo("tc-1");
        assertThat(results.get(1).toolCallId()).isEqualTo("tc-2");
        assertThat(results.get(2).toolCallId()).isEqualTo("tc-3");
    }

    @Test
    void executeToolCallsParallel_immediateResultsHandledCorrectly() {
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        AgentTool tool1 = createTool("tool1", result);
        
        AgentContext ctx = contextWithTools(tool1);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();
        
        // Mix of valid and invalid tool calls
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "nonexistent"),  // Immediate error
                toolCall("tc-3", "tool1"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsParallel(
                ctx, assistantMsg, toolCalls, config, null, stream);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).toolCallId()).isEqualTo("tc-1");
        assertThat(results.get(0).isError()).isFalse();
        assertThat(results.get(1).toolCallId()).isEqualTo("tc-2");
        assertThat(results.get(1).isError()).isTrue();
        assertThat(results.get(2).toolCallId()).isEqualTo("tc-3");
        assertThat(results.get(2).isError()).isFalse();
    }

    // ── executeToolCalls dispatches correctly ────────────────────────────

    @Test
    void executeToolCalls_sequentialMode_usesSequentialExecution() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        
        AgentTool tool1 = createDelayedTool("tool1", result, 50, executionOrder);
        AgentTool tool2 = createDelayedTool("tool2", result, 10, executionOrder);
        
        AgentContext ctx = contextWithTools(tool1, tool2);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.SEQUENTIAL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        ctx.getMessages().add(assistantMsg);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCalls(ctx, assistantMsg, config, null, stream);

        // Sequential: tool1 should complete before tool2 starts
        assertThat(executionOrder).containsExactly("tool1", "tool2");
    }

    @Test
    void executeToolCalls_parallelMode_usesParallelExecution() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        
        // tool1 is slow, tool2 is fast
        AgentTool tool1 = createDelayedTool("tool1", result, 100, executionOrder);
        AgentTool tool2 = createDelayedTool("tool2", result, 10, executionOrder);
        
        AgentContext ctx = contextWithTools(tool1, tool2);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .toolExecution(ToolExecutionMode.PARALLEL)
                .build();
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        ctx.getMessages().add(assistantMsg);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCalls(ctx, assistantMsg, config, null, stream);

        // Parallel: tool2 should complete before tool1 (it's faster)
        assertThat(executionOrder).containsExactly("tool2", "tool1");
    }

    @Test
    void executeToolCalls_defaultMode_isParallel() {
        List<String> executionOrder = new CopyOnWriteArrayList<>();
        AgentToolResult<?> result = new AgentToolResult<>(List.of(new TextContent("done")), null);
        
        // tool1 is slow, tool2 is fast
        AgentTool tool1 = createDelayedTool("tool1", result, 100, executionOrder);
        AgentTool tool2 = createDelayedTool("tool2", result, 10, executionOrder);
        
        AgentContext ctx = contextWithTools(tool1, tool2);
        AgentLoopConfig config = AgentLoopConfig.builder().build(); // default mode
        
        List<ToolCall> toolCalls = List.of(
                toolCall("tc-1", "tool1"),
                toolCall("tc-2", "tool2"));
        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        ctx.getMessages().add(assistantMsg);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executeToolCalls(ctx, assistantMsg, config, null, stream);

        // Default is PARALLEL: tool2 should complete before tool1
        assertThat(executionOrder).containsExactly("tool2", "tool1");
    }

    @Test
    void executeToolCalls_noToolCalls_returnsEmptyList() {
        AgentContext ctx = contextWithTools();
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        
        // Assistant message with no tool calls
        AssistantMessage msg = AssistantMessage.builder()
                .content(List.of(new TextContent("Hello")))
                .stopReason(StopReason.STOP)
                .usage(new Usage(0, 0, 0, 0, 0, null))
                .timestamp(System.currentTimeMillis())
                .build();
        AgentMessage assistantMsg = MessageAdapter.wrap(msg);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCalls(
                ctx, assistantMsg, config, null, stream);

        assertThat(results).isEmpty();
    }
}

package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.config.StreamFn;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.event.EventStream;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.Usage;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 13: Steering 消息注入时机
 *
 * <p>For any Agent loop execution, steering messages should be injected into the context
 * after tool execution completes and before the next LLM call. For each steering message,
 * message_start and message_end events should be emitted.
 *
 * <p><b>Validates: Requirements 16.3, 16.4, 28.6</b>
 */
class SteeringInjectionPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> steeringMessageCounts() {
        return Arbitraries.integers().between(1, 3);
    }

    @Provide
    Arbitrary<String> messageContents() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    // ==================== Helpers ====================

    private static AgentMessage createSteeringMessage(String content) {
        return new AgentMessage() {
            @Override public String role() { return "user"; }
            @Override public long timestamp() { return System.currentTimeMillis(); }
            @Override public String toString() { return "SteeringMessage[" + content + "]"; }
        };
    }

    private static AgentMessage stubUserMessage() {
        return new AgentMessage() {
            @Override public String role() { return "user"; }
            @Override public long timestamp() { return System.currentTimeMillis(); }
        };
    }

    private static AgentTool createTool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Test tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("done")), null));
            }
        };
    }

    /**
     * Creates a mock StreamFn that returns tool calls on first call, then STOP.
     */
    private static StreamFn mockStreamFnWithToolCalls(List<ToolCall> toolCalls) {
        final AtomicInteger callCount = new AtomicInteger(0);
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            int count = callCount.incrementAndGet();
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1 && !toolCalls.isEmpty()) {
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                content = List.of(new TextContent("Final response"));
                stopReason = StopReason.STOP;
            }
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .stopReason(stopReason)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(stopReason, msg));
                stream.end(msg);
            });
            return stream;
        };
    }

    private static StreamFn mockStreamFnStop() {
        return mockStreamFnWithToolCalls(List.of());
    }

    private static List<AgentEvent> collectEvents(EventStream<AgentEvent, List<AgentMessage>> stream) {
        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    // ==================== Property Tests ====================

    /**
     * Property 13: Steering messages emit message_start and message_end events.
     *
     * <p>Verifies that for each steering message, both message_start and message_end
     * events are emitted.
     */
    @Property(tries = 100)
    void steeringInjection_emitsMessageStartAndEndEvents(
            @ForAll("steeringMessageCounts") int steeringCount
    ) {
        // Create steering messages queue
        ConcurrentLinkedQueue<AgentMessage> steeringQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> steeringMessages = new ArrayList<>();
        for (int i = 0; i < steeringCount; i++) {
            AgentMessage msg = createSteeringMessage("steering-" + i);
            steeringMessages.add(msg);
        }

        // Create a tool that will trigger steering message injection
        String toolName = "testTool";
        AgentTool tool = createTool(toolName);
        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", toolName, Map.of()));

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tool))
                .build();

        // Track LLM call count to inject steering after first tool execution
        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> {
                    // Return steering messages after first LLM call (after tool execution)
                    if (llmCallCount.get() == 1 && !steeringQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = steeringQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        // Add steering messages to queue
        steeringQueue.addAll(steeringMessages);

        // Create StreamFn that tracks calls
        StreamFn streamFn = (model, context, options) -> {
            int count = llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1) {
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                content = List.of(new TextContent("Final response"));
                stopReason = StopReason.STOP;
            }
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .stopReason(stopReason)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(stopReason, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Count message_start events for steering messages
        long steeringMessageStartCount = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageStart ms
                        && steeringMessages.contains(ms.message()))
                .count();

        // Count message_end events for steering messages
        long steeringMessageEndCount = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageEnd me
                        && steeringMessages.contains(me.message()))
                .count();

        // Each steering message should have both start and end events
        assertThat(steeringMessageStartCount).isEqualTo(steeringCount);
        assertThat(steeringMessageEndCount).isEqualTo(steeringCount);
    }

    /**
     * Property 13: Steering messages are injected after tool execution.
     *
     * <p>Verifies that steering message events appear after tool_execution_end events.
     */
    @Property(tries = 100)
    void steeringInjection_afterToolExecution(
            @ForAll("steeringMessageCounts") int steeringCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> steeringQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> steeringMessages = new ArrayList<>();
        for (int i = 0; i < steeringCount; i++) {
            AgentMessage msg = createSteeringMessage("steering-" + i);
            steeringMessages.add(msg);
        }

        String toolName = "testTool";
        AgentTool tool = createTool(toolName);
        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", toolName, Map.of()));

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tool))
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> {
                    if (llmCallCount.get() == 1 && !steeringQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = steeringQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        steeringQueue.addAll(steeringMessages);

        StreamFn streamFn = (model, context, options) -> {
            int count = llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1) {
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                content = List.of(new TextContent("Final response"));
                stopReason = StopReason.STOP;
            }
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .stopReason(stopReason)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(stopReason, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Find the last tool_execution_end index
        int lastToolEndIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.ToolExecutionEnd) {
                lastToolEndIndex = i;
            }
        }

        // Find the first steering message_start index
        int firstSteeringStartIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageStart ms
                    && steeringMessages.contains(ms.message())) {
                firstSteeringStartIndex = i;
                break;
            }
        }

        // If we have both tool execution and steering messages,
        // steering should come after tool execution
        if (lastToolEndIndex >= 0 && firstSteeringStartIndex >= 0) {
            assertThat(firstSteeringStartIndex).isGreaterThan(lastToolEndIndex);
        }
    }

    /**
     * Property 13: Steering messages are injected before next LLM call.
     *
     * <p>Verifies that steering messages appear in the context before the next
     * assistant message is generated.
     */
    @Property(tries = 100)
    void steeringInjection_beforeNextLlmCall(
            @ForAll("steeringMessageCounts") int steeringCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> steeringQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> steeringMessages = new ArrayList<>();
        for (int i = 0; i < steeringCount; i++) {
            AgentMessage msg = createSteeringMessage("steering-" + i);
            steeringMessages.add(msg);
        }

        String toolName = "testTool";
        AgentTool tool = createTool(toolName);
        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", toolName, Map.of()));

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tool))
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> {
                    if (llmCallCount.get() == 1 && !steeringQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = steeringQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        steeringQueue.addAll(steeringMessages);

        StreamFn streamFn = (model, context, options) -> {
            int count = llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1) {
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                content = List.of(new TextContent("Final response"));
                stopReason = StopReason.STOP;
            }
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .stopReason(stopReason)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(stopReason, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Find the last steering message_end index
        int lastSteeringEndIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageEnd me
                    && steeringMessages.contains(me.message())) {
                lastSteeringEndIndex = i;
            }
        }

        // Find the second assistant message_start index (after steering)
        int secondAssistantStartIndex = -1;
        int assistantCount = 0;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageStart ms
                    && "assistant".equals(ms.message().role())) {
                assistantCount++;
                if (assistantCount == 2) {
                    secondAssistantStartIndex = i;
                    break;
                }
            }
        }

        // If we have steering messages and a second assistant message,
        // steering should come before the second assistant message
        if (lastSteeringEndIndex >= 0 && secondAssistantStartIndex >= 0) {
            assertThat(lastSteeringEndIndex).isLessThan(secondAssistantStartIndex);
        }
    }

    /**
     * Property 13: Steering messages are added to context.
     *
     * <p>Verifies that steering messages are added to the agent context.
     */
    @Property(tries = 100)
    void steeringInjection_addedToContext(
            @ForAll("steeringMessageCounts") int steeringCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> steeringQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> steeringMessages = new ArrayList<>();
        for (int i = 0; i < steeringCount; i++) {
            AgentMessage msg = createSteeringMessage("steering-" + i);
            steeringMessages.add(msg);
        }

        String toolName = "testTool";
        AgentTool tool = createTool(toolName);
        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", toolName, Map.of()));

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tool))
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> {
                    if (llmCallCount.get() == 1 && !steeringQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = steeringQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        steeringQueue.addAll(steeringMessages);

        StreamFn streamFn = (model, context, options) -> {
            int count = llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1) {
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                content = List.of(new TextContent("Final response"));
                stopReason = StopReason.STOP;
            }
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(content)
                    .stopReason(stopReason)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(stopReason, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        // Consume all events
        collectEvents(stream);

        // Verify steering messages are in context
        for (AgentMessage steeringMsg : steeringMessages) {
            assertThat(ctx.getMessages()).contains(steeringMsg);
        }
    }
}

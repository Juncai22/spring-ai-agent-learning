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
 * Feature: pi-agent-core-java, Property 14: FollowUp 消息处理时机
 *
 * <p>For any Agent loop execution, followUp messages should only be processed when:
 * <ul>
 *   <li>There are no more tool calls</li>
 *   <li>There are no steering messages</li>
 * </ul>
 * When followUp messages exist, the loop should continue rather than terminate.
 *
 * <p><b>Validates: Requirements 16.5, 16.6, 29.1</b>
 */
class FollowUpTimingPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> followUpMessageCounts() {
        return Arbitraries.integers().between(1, 3);
    }

    @Provide
    Arbitrary<String> messageContents() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    // ==================== Helpers ====================

    private static AgentMessage createFollowUpMessage(String content) {
        return new AgentMessage() {
            private final String id = content;
            @Override public String role() { return "user"; }
            @Override public long timestamp() { return System.currentTimeMillis(); }
            @Override public String toString() { return "FollowUpMessage[" + id + "]"; }
            @Override public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof AgentMessage)) return false;
                return this.toString().equals(o.toString());
            }
            @Override public int hashCode() { return id.hashCode(); }
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

    private static List<AgentEvent> collectEvents(EventStream<AgentEvent, List<AgentMessage>> stream) {
        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    // ==================== Property Tests ====================

    /**
     * Property 14: FollowUp messages are processed only when no tool calls.
     *
     * <p>Verifies that followUp messages are not processed while there are pending
     * tool calls. They should only be processed after all tool calls complete.
     */
    @Property(tries = 100)
    void followUpTiming_processedOnlyWhenNoToolCalls(
            @ForAll("followUpMessageCounts") int followUpCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> followUpQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> followUpMessages = new ArrayList<>();
        for (int i = 0; i < followUpCount; i++) {
            AgentMessage msg = createFollowUpMessage("followup-" + i);
            followUpMessages.add(msg);
        }

        String toolName = "testTool";
        AgentTool tool = createTool(toolName);
        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", toolName, Map.of()));

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tool))
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        // FollowUp messages should be returned after the loop would normally stop
        // (after tool execution completes and no more tool calls)
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getFollowUpMessages(() -> {
                    // Return followUp messages only after second LLM call (when loop would stop)
                    if (llmCallCount.get() >= 2 && !followUpQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = followUpQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        // Add followUp messages to queue
        followUpQueue.addAll(followUpMessages);

        StreamFn streamFn = (model, context, options) -> {
            int count = llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content;
            StopReason stopReason;
            
            if (count == 1) {
                // First call: return tool calls
                content = new ArrayList<>(toolCalls);
                stopReason = StopReason.TOOL_USE;
            } else {
                // Subsequent calls: return STOP (no more tool calls)
                content = List.of(new TextContent("Response " + count));
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

        // Find tool_execution_end events
        List<Integer> toolEndIndices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.ToolExecutionEnd) {
                toolEndIndices.add(i);
            }
        }

        // Find followUp message_start events
        List<Integer> followUpStartIndices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageStart ms
                    && followUpMessages.stream().anyMatch(f -> f.toString().equals(ms.message().toString()))) {
                followUpStartIndices.add(i);
            }
        }

        // If we have both tool executions and followUp messages,
        // followUp should come after all tool executions
        if (!toolEndIndices.isEmpty() && !followUpStartIndices.isEmpty()) {
            int lastToolEndIndex = toolEndIndices.get(toolEndIndices.size() - 1);
            int firstFollowUpIndex = followUpStartIndices.get(0);
            assertThat(firstFollowUpIndex).isGreaterThan(lastToolEndIndex);
        }
    }

    /**
     * Property 14: FollowUp messages cause loop to continue.
     *
     * <p>Verifies that when followUp messages exist, the loop continues
     * rather than terminating.
     */
    @Property(tries = 100)
    void followUpTiming_loopContinuesWhenFollowUpExists(
            @ForAll("followUpMessageCounts") int followUpCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> followUpQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> followUpMessages = new ArrayList<>();
        for (int i = 0; i < followUpCount; i++) {
            AgentMessage msg = createFollowUpMessage("followup-" + i);
            followUpMessages.add(msg);
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getFollowUpMessages(() -> {
                    // Return followUp messages after first LLM call
                    if (llmCallCount.get() == 1 && !followUpQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = followUpQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        followUpQueue.addAll(followUpMessages);

        StreamFn streamFn = (model, context, options) -> {
            llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Response")))
                    .stopReason(StopReason.STOP)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Loop should have continued due to followUp messages
        // We should have more than one LLM call (initial + after followUp)
        assertThat(llmCallCount.get()).isGreaterThanOrEqualTo(2);

        // FollowUp messages should have message_start/end events
        long followUpMessageStartCount = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageStart ms
                        && followUpMessages.stream().anyMatch(f -> f.toString().equals(ms.message().toString())))
                .count();

        assertThat(followUpMessageStartCount).isEqualTo(followUpCount);
    }

    /**
     * Property 14: FollowUp messages are not processed when steering exists.
     *
     * <p>Verifies that followUp messages are only processed when there are
     * no steering messages pending.
     */
    @Property(tries = 100)
    void followUpTiming_notProcessedWhenSteeringExists(
            @ForAll("followUpMessageCounts") int followUpCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> steeringQueue = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<AgentMessage> followUpQueue = new ConcurrentLinkedQueue<>();
        
        List<AgentMessage> steeringMessages = new ArrayList<>();
        AgentMessage steeringMsg = createFollowUpMessage("steering-0");
        steeringMessages.add(steeringMsg);
        
        List<AgentMessage> followUpMessages = new ArrayList<>();
        for (int i = 0; i < followUpCount; i++) {
            AgentMessage msg = createFollowUpMessage("followup-" + i);
            followUpMessages.add(msg);
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getSteeringMessages(() -> {
                    // Return steering messages after first LLM call
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
                .getFollowUpMessages(() -> {
                    // Return followUp messages after second LLM call
                    if (llmCallCount.get() >= 2 && !followUpQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = followUpQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        steeringQueue.addAll(steeringMessages);
        followUpQueue.addAll(followUpMessages);

        StreamFn streamFn = (model, context, options) -> {
            llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Response")))
                    .stopReason(StopReason.STOP)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Find steering message_start indices
        List<Integer> steeringStartIndices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageStart ms
                    && steeringMessages.stream().anyMatch(s -> s.toString().equals(ms.message().toString()))) {
                steeringStartIndices.add(i);
            }
        }

        // Find followUp message_start indices
        List<Integer> followUpStartIndices = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.MessageStart ms
                    && followUpMessages.stream().anyMatch(f -> f.toString().equals(ms.message().toString()))) {
                followUpStartIndices.add(i);
            }
        }

        // Steering should be processed before followUp
        if (!steeringStartIndices.isEmpty() && !followUpStartIndices.isEmpty()) {
            int lastSteeringIndex = steeringStartIndices.get(steeringStartIndices.size() - 1);
            int firstFollowUpIndex = followUpStartIndices.get(0);
            assertThat(firstFollowUpIndex).isGreaterThan(lastSteeringIndex);
        }
    }

    /**
     * Property 14: FollowUp messages are added to context.
     *
     * <p>Verifies that followUp messages are added to the agent context.
     */
    @Property(tries = 100)
    void followUpTiming_addedToContext(
            @ForAll("followUpMessageCounts") int followUpCount
    ) {
        ConcurrentLinkedQueue<AgentMessage> followUpQueue = new ConcurrentLinkedQueue<>();
        List<AgentMessage> followUpMessages = new ArrayList<>();
        for (int i = 0; i < followUpCount; i++) {
            AgentMessage msg = createFollowUpMessage("followup-" + i);
            followUpMessages.add(msg);
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getFollowUpMessages(() -> {
                    if (llmCallCount.get() == 1 && !followUpQueue.isEmpty()) {
                        List<AgentMessage> messages = new ArrayList<>();
                        AgentMessage msg;
                        while ((msg = followUpQueue.poll()) != null) {
                            messages.add(msg);
                        }
                        return CompletableFuture.completedFuture(messages);
                    }
                    return CompletableFuture.completedFuture(List.of());
                })
                .build();

        followUpQueue.addAll(followUpMessages);

        StreamFn streamFn = (model, context, options) -> {
            llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Response")))
                    .stopReason(StopReason.STOP)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        // Consume all events
        collectEvents(stream);

        // Verify followUp messages are in context
        for (AgentMessage followUpMsg : followUpMessages) {
            boolean found = ctx.getMessages().stream()
                    .anyMatch(m -> m.toString().equals(followUpMsg.toString()));
            assertThat(found)
                    .as("FollowUp message %s should be in context", followUpMsg)
                    .isTrue();
        }
    }

    /**
     * Property 14: Loop terminates when no followUp messages.
     *
     * <p>Verifies that when there are no followUp messages (and no tool calls
     * or steering), the loop terminates normally.
     */
    @Property(tries = 100)
    void followUpTiming_loopTerminatesWhenNoFollowUp() {
        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .build();

        AtomicInteger llmCallCount = new AtomicInteger(0);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .getFollowUpMessages(() -> CompletableFuture.completedFuture(List.of()))
                .build();

        StreamFn streamFn = (model, context, options) -> {
            llmCallCount.incrementAndGet();
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Response")))
                    .stopReason(StopReason.STOP)
                    .usage(new Usage(10, 20, 0, 0, 30, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                stream.end(msg);
            });
            return stream;
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Loop should terminate after one LLM call (no followUp to continue)
        assertThat(llmCallCount.get()).isEqualTo(1);

        // Should end with agent_end
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);
    }
}

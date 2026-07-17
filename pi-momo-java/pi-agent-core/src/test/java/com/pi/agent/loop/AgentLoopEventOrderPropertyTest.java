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
import com.pi.agent.types.MessageAdapter;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 4: Agent 循环事件顺序
 *
 * <p>For any Agent loop execution (regardless of number of turns and tool calls),
 * the collected AgentEvent sequence should satisfy the following order constraints:
 * <ul>
 *   <li>Sequence starts with agent_start and ends with agent_end</li>
 *   <li>Each turn_start must have a corresponding turn_end</li>
 *   <li>Each message_start must have a corresponding message_end (with 0 or more message_update in between)</li>
 *   <li>Each tool_execution_start must have a corresponding tool_execution_end (with 0 or more tool_execution_update in between)</li>
 *   <li>turn_start must appear after agent_start</li>
 *   <li>message_start must appear after turn_start</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 14.4, 16.9, 16.10, 11.2, 11.4, 11.6, 11.8</b>
 */
class AgentLoopEventOrderPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> toolCallCounts() {
        return Arbitraries.integers().between(0, 3);
    }

    @Provide
    Arbitrary<String> toolNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    // ==================== Helpers ====================

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
     * Creates a mock StreamFn that returns an AssistantMessage with the specified tool calls.
     */
    private static StreamFn mockStreamFnWithToolCalls(List<ToolCall> toolCalls) {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            
            List<com.pi.ai.core.types.AssistantContentBlock> content = new ArrayList<>(toolCalls);
            StopReason stopReason = toolCalls.isEmpty() ? StopReason.STOP : StopReason.TOOL_USE;
            
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

    /**
     * Creates a mock StreamFn that returns a simple STOP response (no tool calls).
     */
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
     * Property 4: Agent loop event order - basic sequence constraints.
     *
     * <p>Verifies that for any agent loop execution:
     * <ul>
     *   <li>Sequence starts with agent_start</li>
     *   <li>Sequence ends with agent_end</li>
     *   <li>turn_start appears after agent_start</li>
     * </ul>
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_startsWithAgentStartEndsWithAgentEnd(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        // Setup tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        // First call returns tool calls, second call returns STOP
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Verify sequence starts with agent_start
        assertThat(events).isNotEmpty();
        assertThat(events.get(0)).isInstanceOf(AgentEvent.AgentStart.class);

        // Verify sequence ends with agent_end
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);
    }

    /**
     * Property 4: Agent loop event order - turn_start/turn_end pairing.
     *
     * <p>Verifies that each turn_start has a corresponding turn_end.
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_turnStartHasMatchingTurnEnd(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Count turn_start and turn_end events
        long turnStartCount = events.stream()
                .filter(e -> e instanceof AgentEvent.TurnStart)
                .count();
        long turnEndCount = events.stream()
                .filter(e -> e instanceof AgentEvent.TurnEnd)
                .count();

        assertThat(turnStartCount).isEqualTo(turnEndCount);
        assertThat(turnStartCount).isGreaterThanOrEqualTo(1);
    }

    /**
     * Property 4: Agent loop event order - message_start/message_end pairing.
     *
     * <p>Verifies that each message_start has a corresponding message_end.
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_messageStartHasMatchingMessageEnd(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Count message_start and message_end events
        long messageStartCount = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageStart)
                .count();
        long messageEndCount = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageEnd)
                .count();

        assertThat(messageStartCount).isEqualTo(messageEndCount);
    }

    /**
     * Property 4: Agent loop event order - tool_execution_start/tool_execution_end pairing.
     *
     * <p>Verifies that each tool_execution_start has a corresponding tool_execution_end.
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_toolExecutionStartHasMatchingToolExecutionEnd(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Count tool_execution_start and tool_execution_end events
        long toolStartCount = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionStart)
                .count();
        long toolEndCount = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .count();

        assertThat(toolStartCount).isEqualTo(toolEndCount);
        assertThat(toolStartCount).isEqualTo(toolCallCount);
    }

    /**
     * Property 4: Agent loop event order - turn_start appears after agent_start.
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_turnStartAfterAgentStart(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Find index of first agent_start and first turn_start
        int agentStartIndex = -1;
        int firstTurnStartIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.AgentStart && agentStartIndex == -1) {
                agentStartIndex = i;
            }
            if (events.get(i) instanceof AgentEvent.TurnStart && firstTurnStartIndex == -1) {
                firstTurnStartIndex = i;
            }
        }

        assertThat(agentStartIndex).isGreaterThanOrEqualTo(0);
        assertThat(firstTurnStartIndex).isGreaterThan(agentStartIndex);
    }

    /**
     * Property 4: Agent loop event order - message_start appears after turn_start.
     */
    @Property(tries = 100)
    void agentLoop_eventSequence_messageStartAfterTurnStart(
            @ForAll("toolCallCounts") int toolCallCount
    ) {
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        AgentContext ctx = AgentContext.builder()
                .systemPrompt("test")
                .tools(tools)
                .build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(stubUserMessage()), ctx, config, null, streamFn);

        List<AgentEvent> events = collectEvents(stream);

        // Find index of first turn_start and first message_start
        int firstTurnStartIndex = -1;
        int firstMessageStartIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i) instanceof AgentEvent.TurnStart && firstTurnStartIndex == -1) {
                firstTurnStartIndex = i;
            }
            if (events.get(i) instanceof AgentEvent.MessageStart && firstMessageStartIndex == -1) {
                firstMessageStartIndex = i;
            }
        }

        assertThat(firstTurnStartIndex).isGreaterThanOrEqualTo(0);
        assertThat(firstMessageStartIndex).isGreaterThan(firstTurnStartIndex);
    }
}

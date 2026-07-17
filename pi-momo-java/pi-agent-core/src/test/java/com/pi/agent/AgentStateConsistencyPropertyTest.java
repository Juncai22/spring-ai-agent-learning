package com.pi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.config.StreamFn;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentOptions;
import com.pi.agent.types.AgentState;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.Usage;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for Agent class state consistency.
 *
 * <p>Property 8: Agent 状态一致性（isStreaming 生命周期）
 * For any Agent loop execution (via prompt() or continue()), isStreaming should be true
 * after loop starts, and false after loop ends (normal or exception). streamMessage should
 * be null and pendingToolCalls should be empty after loop ends.
 *
 * <p>Property 9: pendingToolCalls 追踪一致性
 * For any Agent loop execution with tool calls, tool_execution_start should add toolCallId
 * to pendingToolCalls, tool_execution_end should remove it, and loop end should have empty
 * pendingToolCalls.
 *
 * <p><b>Validates: Requirements 10.2, 10.3, 10.4, 10.5, 31.1, 31.3, 31.5, 32.4, 32.5, 32.6</b>
 */
class AgentStateConsistencyPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> toolCallCounts() {
        return Arbitraries.integers().between(0, 3);
    }

    // ==================== Helpers ====================

    private static Model createTestModel() {
        return new Model(
                "test-model",           // id
                "Test Model",           // name
                "test-api",             // api
                "test-provider",        // provider
                "https://test.api",     // baseUrl
                false,                  // reasoning
                List.of("text"),        // input
                null,                   // cost
                4096,                   // contextWindow
                1024,                   // maxTokens
                null,                   // headers
                null                    // compat
        );
    }

    private static AgentTool createTool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Test tool: " + name;
            }

            @Override
            public JsonNode parameters() {
                return null;
            }

            @Override
            public CompletableFuture<AgentToolResult<?>> execute(
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

    /**
     * Creates a mock StreamFn that throws an exception.
     */
    private static StreamFn mockStreamFnWithException(String errorMessage) {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();

            CompletableFuture.runAsync(() -> {
                // Create an error AssistantMessage
                AssistantMessage errorMsg = AssistantMessage.builder()
                        .content(List.of(new TextContent("")))
                        .stopReason(StopReason.ERROR)
                        .errorMessage(errorMessage)
                        .usage(new Usage(0, 0, 0, 0, 0, null))
                        .timestamp(System.currentTimeMillis())
                        .build();
                stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, errorMsg));
                stream.end(errorMsg);
            });
            return stream;
        };
    }

    // ==================== Property 8: Agent 状态一致性（isStreaming 生命周期）====================

    /**
     * Property 8: Agent state consistency - isStreaming lifecycle (normal completion).
     *
     * <p>Verifies that:
     * <ul>
     *   <li>isStreaming=true after agent_start event</li>
     *   <li>isStreaming=false after agent_end event</li>
     *   <li>streamMessage=null after loop ends</li>
     *   <li>pendingToolCalls is empty after loop ends</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 10.2, 10.3, 31.1, 31.3, 31.5, 32.6</b>
     */
    @Property(tries = 50)
    void agentState_isStreamingLifecycle_normalCompletion(
            @ForAll("toolCallCounts") int toolCallCount
    ) throws Exception {
        // Setup tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        // Track state at different points
        AtomicBoolean isStreamingAfterStart = new AtomicBoolean(false);
        AtomicBoolean isStreamingAfterEnd = new AtomicBoolean(true);
        AtomicReference<AgentMessage> streamMessageAfterEnd = new AtomicReference<>(new AgentMessage() {
            @Override public String role() { return "dummy"; }
            @Override public long timestamp() { return 0; }
        });
        AtomicReference<Set<String>> pendingToolCallsAfterEnd = new AtomicReference<>(Set.of("dummy"));

        // Create agent with mock streamFn
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());
        agent.setTools(tools);

        // Subscribe to events to track state
        CountDownLatch latch = new CountDownLatch(1);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.AgentStart) {
                // Capture isStreaming state after agent_start
                isStreamingAfterStart.set(agent.getState().isStreaming());
            } else if (event instanceof AgentEvent.AgentEnd) {
                // Capture state after agent_end
                isStreamingAfterEnd.set(agent.getState().isStreaming());
                streamMessageAfterEnd.set(agent.getState().getStreamMessage());
                pendingToolCallsAfterEnd.set(agent.getState().getPendingToolCalls());
                latch.countDown();
            }
        });

        // Execute prompt
        agent.prompt("test message");

        // Wait for completion
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify state consistency
        assertThat(isStreamingAfterStart.get())
                .as("isStreaming should be true after agent_start")
                .isTrue();
        assertThat(isStreamingAfterEnd.get())
                .as("isStreaming should be false after agent_end")
                .isFalse();
        assertThat(streamMessageAfterEnd.get())
                .as("streamMessage should be null after loop ends")
                .isNull();
        assertThat(pendingToolCallsAfterEnd.get())
                .as("pendingToolCalls should be empty after loop ends")
                .isEmpty();
    }


    /**
     * Property 8: Agent state consistency - isStreaming lifecycle (exception scenario).
     *
     * <p>Verifies that even when an exception occurs during the loop:
     * <ul>
     *   <li>isStreaming=false after loop ends</li>
     *   <li>streamMessage=null after loop ends</li>
     *   <li>pendingToolCalls is empty after loop ends</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 10.2, 10.3, 31.5, 32.6</b>
     */
    @Property(tries = 20)
    void agentState_isStreamingLifecycle_exceptionScenario() throws Exception {
        // Track state after exception
        AtomicBoolean isStreamingAfterEnd = new AtomicBoolean(true);
        AtomicReference<AgentMessage> streamMessageAfterEnd = new AtomicReference<>(new AgentMessage() {
            @Override public String role() { return "dummy"; }
            @Override public long timestamp() { return 0; }
        });
        AtomicReference<Set<String>> pendingToolCallsAfterEnd = new AtomicReference<>(Set.of("dummy"));

        // Create agent with exception-throwing streamFn
        StreamFn streamFn = mockStreamFnWithException("Test exception");

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());

        // Subscribe to events to track state
        CountDownLatch latch = new CountDownLatch(1);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.AgentEnd) {
                // Capture state after agent_end (even on exception)
                isStreamingAfterEnd.set(agent.getState().isStreaming());
                streamMessageAfterEnd.set(agent.getState().getStreamMessage());
                pendingToolCallsAfterEnd.set(agent.getState().getPendingToolCalls());
                latch.countDown();
            }
        });

        // Execute prompt (will cause exception)
        agent.prompt("test message");

        // Wait for completion
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify state consistency after exception
        assertThat(isStreamingAfterEnd.get())
                .as("isStreaming should be false after exception")
                .isFalse();
        assertThat(streamMessageAfterEnd.get())
                .as("streamMessage should be null after exception")
                .isNull();
        assertThat(pendingToolCallsAfterEnd.get())
                .as("pendingToolCalls should be empty after exception")
                .isEmpty();
    }

    /**
     * Property 8: Agent state consistency - final state after waitForIdle.
     *
     * <p>Verifies that after waitForIdle() completes:
     * <ul>
     *   <li>isStreaming=false</li>
     *   <li>streamMessage=null</li>
     *   <li>pendingToolCalls is empty</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 10.2, 10.3, 31.5</b>
     */
    @Property(tries = 30)
    void agentState_finalStateAfterWaitForIdle(
            @ForAll("toolCallCounts") int toolCallCount
    ) throws Exception {
        // Setup tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        // Create agent with mock streamFn
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1 && !toolCalls.isEmpty()) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());
        agent.setTools(tools);

        // Execute prompt
        agent.prompt("test message");

        // Wait for idle
        agent.waitForIdle().get(5, TimeUnit.SECONDS);

        // Verify final state
        AgentState state = agent.getState();
        assertThat(state.isStreaming())
                .as("isStreaming should be false after waitForIdle")
                .isFalse();
        assertThat(state.getStreamMessage())
                .as("streamMessage should be null after waitForIdle")
                .isNull();
        assertThat(state.getPendingToolCalls())
                .as("pendingToolCalls should be empty after waitForIdle")
                .isEmpty();
    }


    // ==================== Property 9: pendingToolCalls 追踪一致性 ====================

    /**
     * Property 9: pendingToolCalls tracking consistency.
     *
     * <p>Verifies that:
     * <ul>
     *   <li>tool_execution_start adds toolCallId to pendingToolCalls</li>
     *   <li>tool_execution_end removes toolCallId from pendingToolCalls</li>
     *   <li>pendingToolCalls is empty after loop ends</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 10.4, 10.5, 32.4, 32.5</b>
     */
    @Property(tries = 50)
    void pendingToolCalls_trackingConsistency(
            @ForAll("toolCallCounts") int toolCallCount
    ) throws Exception {
        // Skip if no tool calls
        Assume.that(toolCallCount > 0);

        // Setup tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        // Track pendingToolCalls state at different events
        List<PendingToolCallsSnapshot> snapshots = new CopyOnWriteArrayList<>();

        // Create agent with mock streamFn
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());
        agent.setTools(tools);

        // Subscribe to events to track pendingToolCalls state
        CountDownLatch latch = new CountDownLatch(1);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
                // Capture state after tool_execution_start
                Set<String> pending = Set.copyOf(agent.getState().getPendingToolCalls());
                snapshots.add(new PendingToolCallsSnapshot(
                        "tool_execution_start",
                        toolStart.toolCallId(),
                        pending,
                        pending.contains(toolStart.toolCallId())
                ));
            } else if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
                // Capture state after tool_execution_end
                Set<String> pending = Set.copyOf(agent.getState().getPendingToolCalls());
                snapshots.add(new PendingToolCallsSnapshot(
                        "tool_execution_end",
                        toolEnd.toolCallId(),
                        pending,
                        !pending.contains(toolEnd.toolCallId())
                ));
            } else if (event instanceof AgentEvent.AgentEnd) {
                // Capture final state
                Set<String> pending = Set.copyOf(agent.getState().getPendingToolCalls());
                snapshots.add(new PendingToolCallsSnapshot(
                        "agent_end",
                        null,
                        pending,
                        pending.isEmpty()
                ));
                latch.countDown();
            }
        });

        // Execute prompt
        agent.prompt("test message");

        // Wait for completion
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify all snapshots
        for (PendingToolCallsSnapshot snapshot : snapshots) {
            assertThat(snapshot.isValid)
                    .as("Snapshot at %s for toolCallId=%s should be valid. pendingToolCalls=%s",
                            snapshot.eventType, snapshot.toolCallId, snapshot.pendingToolCalls)
                    .isTrue();
        }

        // Verify we got the expected number of tool execution events
        long startCount = snapshots.stream()
                .filter(s -> "tool_execution_start".equals(s.eventType))
                .count();
        long endCount = snapshots.stream()
                .filter(s -> "tool_execution_end".equals(s.eventType))
                .count();

        assertThat(startCount)
                .as("Should have %d tool_execution_start events", toolCallCount)
                .isEqualTo(toolCallCount);
        assertThat(endCount)
                .as("Should have %d tool_execution_end events", toolCallCount)
                .isEqualTo(toolCallCount);
    }

    /**
     * Snapshot of pendingToolCalls state at a specific event.
     */
    private record PendingToolCallsSnapshot(
            String eventType,
            String toolCallId,
            Set<String> pendingToolCalls,
            boolean isValid
    ) {}


    /**
     * Property 9: pendingToolCalls tracking - each toolCallId is added exactly once and removed exactly once.
     *
     * <p>Verifies that for each tool call:
     * <ul>
     *   <li>toolCallId is added to pendingToolCalls at tool_execution_start</li>
     *   <li>toolCallId is removed from pendingToolCalls at tool_execution_end</li>
     *   <li>The same toolCallId is not added twice</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 10.4, 10.5, 32.4, 32.5</b>
     */
    @Property(tries = 50)
    void pendingToolCalls_eachToolCallIdAddedAndRemovedOnce(
            @ForAll("toolCallCounts") int toolCallCount
    ) throws Exception {
        // Skip if no tool calls
        Assume.that(toolCallCount > 0);

        // Setup tools and tool calls
        List<AgentTool> tools = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        for (int i = 0; i < toolCallCount; i++) {
            String toolName = "tool" + i;
            tools.add(createTool(toolName));
            toolCalls.add(new ToolCall("tc-" + i, toolName, Map.of()));
        }

        // Track tool call events
        List<String> startedToolCallIds = new CopyOnWriteArrayList<>();
        List<String> endedToolCallIds = new CopyOnWriteArrayList<>();

        // Create agent with mock streamFn
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());
        agent.setTools(tools);

        // Subscribe to events
        CountDownLatch latch = new CountDownLatch(1);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
                startedToolCallIds.add(toolStart.toolCallId());
            } else if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
                endedToolCallIds.add(toolEnd.toolCallId());
            } else if (event instanceof AgentEvent.AgentEnd) {
                latch.countDown();
            }
        });

        // Execute prompt
        agent.prompt("test message");

        // Wait for completion
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify each toolCallId appears exactly once in start and end lists
        assertThat(startedToolCallIds)
                .as("Each toolCallId should be started exactly once")
                .hasSize(toolCallCount)
                .doesNotHaveDuplicates();

        assertThat(endedToolCallIds)
                .as("Each toolCallId should be ended exactly once")
                .hasSize(toolCallCount)
                .doesNotHaveDuplicates();

        // Verify all started tool calls were ended
        assertThat(endedToolCallIds)
                .as("All started tool calls should be ended")
                .containsExactlyInAnyOrderElementsOf(startedToolCallIds);
    }

    /**
     * Property 9: pendingToolCalls tracking - empty at loop end even with multiple turns.
     *
     * <p>Verifies that pendingToolCalls is empty after agent_end even when
     * multiple turns with tool calls occur.
     *
     * <p><b>Validates: Requirements 10.4, 10.5, 32.4, 32.5</b>
     */
    @Property(tries = 30)
    void pendingToolCalls_emptyAtLoopEndWithMultipleTurns() throws Exception {
        // Setup tools
        List<AgentTool> tools = List.of(
                createTool("tool1"),
                createTool("tool2")
        );

        // First turn: 2 tool calls, second turn: 1 tool call, third turn: stop
        List<ToolCall> firstTurnToolCalls = List.of(
                new ToolCall("tc-1", "tool1", Map.of()),
                new ToolCall("tc-2", "tool2", Map.of())
        );
        List<ToolCall> secondTurnToolCalls = List.of(
                new ToolCall("tc-3", "tool1", Map.of())
        );

        // Track final pendingToolCalls state
        AtomicReference<Set<String>> finalPendingToolCalls = new AtomicReference<>();

        // Create agent with mock streamFn that simulates multiple turns
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockStreamFnWithToolCalls(firstTurnToolCalls).stream(model, context, options);
            } else if (callCount[0] == 2) {
                return mockStreamFnWithToolCalls(secondTurnToolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentOptions agentOptions = AgentOptions.builder()
                .streamFn(streamFn)
                .build();

        Agent agent = new Agent(agentOptions);
        agent.setModel(createTestModel());
        agent.setTools(tools);

        // Subscribe to events
        CountDownLatch latch = new CountDownLatch(1);
        agent.subscribe(event -> {
            if (event instanceof AgentEvent.AgentEnd) {
                finalPendingToolCalls.set(Set.copyOf(agent.getState().getPendingToolCalls()));
                latch.countDown();
            }
        });

        // Execute prompt
        agent.prompt("test message");

        // Wait for completion
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Verify pendingToolCalls is empty at the end
        assertThat(finalPendingToolCalls.get())
                .as("pendingToolCalls should be empty after multiple turns")
                .isEmpty();
    }
}

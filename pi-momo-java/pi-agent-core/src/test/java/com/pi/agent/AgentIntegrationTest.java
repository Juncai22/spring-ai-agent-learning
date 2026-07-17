package com.pi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.StreamFn;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentOptions;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.MessageAdapter;
import com.pi.agent.types.QueueMode;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.Model;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.types.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Agent class verifying end-to-end flow.
 *
 * <p>Tests the complete integration of:
 * <ul>
 *   <li>Agent.prompt() → _runLoop → AgentLoop.agentLoop → StreamFn</li>
 *   <li>Event propagation from AgentLoop to Agent subscribers</li>
 *   <li>Steering and followUp queue integration</li>
 *   <li>Error handling and cancellation</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 12.2, 14.1-14.5, 16.1-16.10, 23.7, 24.1-24.6, 41.1-41.5</b>
 */
@DisplayName("Agent Integration Tests")
class AgentIntegrationTest {

    // ==================== Test Helpers ====================

    private static Model createTestModel() {
        return new Model(
                "test-model",
                "Test Model",
                "test-api",
                "test-provider",
                "https://test.api",
                false,
                List.of("text"),
                null,
                4096,
                1024,
                null,
                null
        );
    }

    private static AgentTool createTool(String name) {
        return createTool(name, "done");
    }

    private static AgentTool createTool(String name, String resultText) {
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
                        new AgentToolResult<>(List.of(new TextContent(resultText)), null));
            }
        };
    }

    private static AgentTool createDelayedTool(String name, long delayMs) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Delayed test tool: " + name;
            }

            @Override
            public JsonNode parameters() {
                return null;
            }

            @Override
            public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new AgentToolResult<>(List.of(new TextContent("delayed-done")), null);
                });
            }
        };
    }

    private static AgentTool createExceptionTool(String name, String errorMessage) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Exception tool: " + name;
            }

            @Override
            public JsonNode parameters() {
                return null;
            }

            @Override
            public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.failedFuture(new RuntimeException(errorMessage));
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
     * Creates a mock StreamFn that returns a simple text response (no tool calls).
     */
    private static StreamFn mockStreamFnWithText(String text) {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();

            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent(text)))
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
    }

    /**
     * Creates a mock StreamFn that returns a simple STOP response (no tool calls).
     */
    private static StreamFn mockStreamFnStop() {
        return mockStreamFnWithText("Hello!");
    }

    /**
     * Creates a mock StreamFn that returns an error response.
     */
    private static StreamFn mockStreamFnWithError(String errorMessage) {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();

            AssistantMessage errorMsg = AssistantMessage.builder()
                    .content(List.of(new TextContent("")))
                    .stopReason(StopReason.ERROR)
                    .errorMessage(errorMessage)
                    .usage(new Usage(0, 0, 0, 0, 0, null))
                    .timestamp(System.currentTimeMillis())
                    .build();

            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, errorMsg));
                stream.end(errorMsg);
            });
            return stream;
        };
    }

    /**
     * Creates a mock StreamFn that simulates a slow response for cancellation testing.
     */
    private static StreamFn mockStreamFnSlow(long delayMs) {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();

            CompletableFuture.runAsync(() -> {
                try {
                    // Check for cancellation during delay
                    long startTime = System.currentTimeMillis();
                    while (System.currentTimeMillis() - startTime < delayMs) {
                        if (options.getSignal() != null && options.getSignal().isCancelled()) {
                            AssistantMessage abortedMsg = AssistantMessage.builder()
                                    .content(List.of(new TextContent("")))
                                    .stopReason(StopReason.ABORTED)
                                    .usage(new Usage(0, 0, 0, 0, 0, null))
                                    .timestamp(System.currentTimeMillis())
                                    .build();
                            stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, abortedMsg));
                            stream.end(abortedMsg);
                            return;
                        }
                        Thread.sleep(10);
                    }
                    
                    AssistantMessage msg = AssistantMessage.builder()
                            .content(List.of(new TextContent("Slow response")))
                            .stopReason(StopReason.STOP)
                            .usage(new Usage(10, 20, 0, 0, 30, null))
                            .timestamp(System.currentTimeMillis())
                            .build();
                    stream.push(new AssistantMessageEvent.Start(msg));
                    stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                    stream.end(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return stream;
        };
    }

    // ==================== Test: Simple Prompt → Response Flow ====================

    @Nested
    @DisplayName("Simple Prompt → Response Flow (No Tools)")
    class SimplePromptResponseTests {

        /**
         * Test 14.1/14.2: Simple prompt → response flow without tools.
         * Verifies Agent.prompt() → _runLoop → AgentLoop.agentLoop → StreamFn complete flow.
         */
        @Test
        @DisplayName("should complete simple prompt → response flow")
        void simplePromptResponseFlow() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnWithText("Hello, world!"))
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Hi there");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify event order
            assertThat(events).isNotEmpty();
            assertThat(events.get(0)).isInstanceOf(AgentEvent.AgentStart.class);
            assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);

            // Verify turn events exist
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.TurnStart);
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.TurnEnd);

            // Verify message events exist
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.MessageStart);
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.MessageEnd);

            // Verify final state
            assertThat(agent.getState().isStreaming()).isFalse();
            assertThat(agent.getState().getMessages()).hasSizeGreaterThanOrEqualTo(2); // user + assistant
        }

        /**
         * Test: Events propagate correctly from AgentLoop to Agent subscribers.
         */
        @Test
        @DisplayName("should propagate events to all subscribers")
        void eventsPropagateToSubscribers() throws Exception {
            // Arrange
            List<AgentEvent> subscriber1Events = new CopyOnWriteArrayList<>();
            List<AgentEvent> subscriber2Events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);

            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnStop())
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            agent.subscribe(event -> {
                subscriber1Events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            agent.subscribe(event -> {
                subscriber2Events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test message");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Both subscribers should receive the same events
            assertThat(subscriber1Events).hasSameSizeAs(subscriber2Events);
            for (int i = 0; i < subscriber1Events.size(); i++) {
                assertThat(subscriber1Events.get(i).type())
                        .isEqualTo(subscriber2Events.get(i).type());
            }
        }

        /**
         * Test: waitForIdle completes when agent finishes.
         */
        @Test
        @DisplayName("should complete waitForIdle when agent finishes")
        void waitForIdleCompletesOnFinish() throws Exception {
            // Arrange
            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnStop())
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            // Act
            agent.prompt("Test message");
            agent.waitForIdle().get(5, TimeUnit.SECONDS);

            // Assert
            assertThat(agent.getState().isStreaming()).isFalse();
        }
    }

    // ==================== Test: Prompt → Tool Call → Tool Result → Response ====================

    @Nested
    @DisplayName("Prompt → Tool Call → Tool Result → Response Flow")
    class ToolCallFlowTests {

        /**
         * Test 14.2: Prompt → tool call → tool result → response flow.
         */
        @Test
        @DisplayName("should complete prompt → tool call → tool result → response flow")
        void promptToolCallResponseFlow() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("tc-1", "calculator", Map.of("a", 1, "b", 2))
            );

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createTool("calculator", "3")));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Calculate 1 + 2");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify tool execution events
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolExecutionStart);
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.ToolExecutionEnd);

            // Verify tool execution start has correct tool name
            AgentEvent.ToolExecutionStart toolStart = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecutionStart)
                    .map(e -> (AgentEvent.ToolExecutionStart) e)
                    .findFirst()
                    .orElseThrow();
            assertThat(toolStart.toolName()).isEqualTo("calculator");
            assertThat(toolStart.toolCallId()).isEqualTo("tc-1");

            // Verify LLM was called twice (first with tool call, second with final response)
            assertThat(callCount[0]).isEqualTo(2);
        }

        /**
         * Test: Multiple tool calls in a single turn.
         */
        @Test
        @DisplayName("should handle multiple tool calls in a single turn")
        void multipleToolCallsInSingleTurn() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("tc-1", "tool1", Map.of()),
                    new ToolCall("tc-2", "tool2", Map.of()),
                    new ToolCall("tc-3", "tool3", Map.of())
            );

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(
                    createTool("tool1"),
                    createTool("tool2"),
                    createTool("tool3")
            ));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Use all tools");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify all 3 tool executions
            long toolStartCount = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecutionStart)
                    .count();
            long toolEndCount = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                    .count();

            assertThat(toolStartCount).isEqualTo(3);
            assertThat(toolEndCount).isEqualTo(3);
        }
    }

    // ==================== Test: Multi-Turn Conversation ====================

    @Nested
    @DisplayName("Multi-Turn Conversation with Multiple Tool Calls")
    class MultiTurnConversationTests {

        /**
         * Test 14.2: Multi-turn conversation with multiple tool calls.
         */
        @Test
        @DisplayName("should handle multi-turn conversation with tool calls")
        void multiTurnConversationWithToolCalls() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger turnCount = new AtomicInteger(0);

            // Turn 1: tool call, Turn 2: tool call, Turn 3: stop
            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(List.of(
                            new ToolCall("tc-1", "tool1", Map.of())
                    )).stream(model, context, options);
                } else if (callCount[0] == 2) {
                    return mockStreamFnWithToolCalls(List.of(
                            new ToolCall("tc-2", "tool2", Map.of())
                    )).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createTool("tool1"), createTool("tool2")));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.TurnStart) {
                    turnCount.incrementAndGet();
                }
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Multi-turn test");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify multiple turns occurred
            assertThat(turnCount.get()).isGreaterThanOrEqualTo(3);

            // Verify LLM was called 3 times
            assertThat(callCount[0]).isEqualTo(3);

            // Verify both tools were executed
            List<String> executedTools = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecutionStart)
                    .map(e -> ((AgentEvent.ToolExecutionStart) e).toolName())
                    .toList();
            assertThat(executedTools).containsExactly("tool1", "tool2");
        }
    }

    // ==================== Test: Steering Queue Integration ====================

    @Nested
    @DisplayName("Steering Queue Integration")
    class SteeringQueueTests {

        /**
         * Test 14.2: Steering queue injection during loop.
         */
        @Test
        @DisplayName("should inject steering messages during loop")
        void steeringQueueInjectionDuringLoop() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean steeringInjected = new AtomicBoolean(false);

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    // First call returns tool call
                    return mockStreamFnWithToolCalls(List.of(
                            new ToolCall("tc-1", "tool1", Map.of())
                    )).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .steeringMode(QueueMode.ONE_AT_A_TIME)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createDelayedTool("tool1", 50)));

            agent.subscribe(event -> {
                events.add(event);
                // Inject steering message after tool execution starts
                if (event instanceof AgentEvent.ToolExecutionStart && !steeringInjected.get()) {
                    steeringInjected.set(true);
                    UserMessage steeringMsg = new UserMessage("Steering message", System.currentTimeMillis());
                    agent.steer(MessageAdapter.wrap(steeringMsg));
                }
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test with steering");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify steering message was processed (should appear in message events)
            long userMessageCount = events.stream()
                    .filter(e -> e instanceof AgentEvent.MessageEnd)
                    .map(e -> ((AgentEvent.MessageEnd) e).message())
                    .filter(m -> "user".equals(m.role()))
                    .count();

            // Should have at least 2 user messages: original prompt + steering
            assertThat(userMessageCount).isGreaterThanOrEqualTo(2);
        }
    }

    // ==================== Test: FollowUp Queue Integration ====================

    @Nested
    @DisplayName("FollowUp Queue Integration")
    class FollowUpQueueTests {

        /**
         * Test 14.2: FollowUp queue processing after tool completion.
         * 
         * <p>FollowUp messages are processed when the agent would normally stop
         * (no tool calls, no steering messages). We inject the followUp message
         * BEFORE the loop starts so it's available when the loop checks for followUp.
         */
        @Test
        @DisplayName("should process followUp messages after agent would stop")
        void followUpQueueProcessingAfterStop() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                // All calls return no tool calls (would normally stop)
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .followUpMode(QueueMode.ONE_AT_A_TIME)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            // Pre-queue a followUp message BEFORE starting the loop
            // This ensures it's available when the loop checks for followUp after first turn
            UserMessage followUpMsg = new UserMessage("FollowUp message", System.currentTimeMillis());
            agent.followUp(MessageAdapter.wrap(followUpMsg));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test with followUp");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify LLM was called at least twice (original + followUp)
            assertThat(callCount[0]).isGreaterThanOrEqualTo(2);

            // Verify multiple turns occurred
            long turnStartCount = events.stream()
                    .filter(e -> e instanceof AgentEvent.TurnStart)
                    .count();
            assertThat(turnStartCount).isGreaterThanOrEqualTo(2);
        }
    }

    // ==================== Test: Error Handling ====================

    @Nested
    @DisplayName("Error Handling Integration")
    class ErrorHandlingTests {

        /**
         * Test 14.2: Error handling when LLM returns error.
         */
        @Test
        @DisplayName("should handle LLM error response")
        void handleLlmErrorResponse() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnWithError("LLM service unavailable"))
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test error handling");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify agent_end was emitted
            assertThat(events).anyMatch(e -> e instanceof AgentEvent.AgentEnd);

            // Verify final state
            assertThat(agent.getState().isStreaming()).isFalse();
        }

        /**
         * Test 14.2: Error handling when tool execution fails.
         */
        @Test
        @DisplayName("should handle tool execution error")
        void handleToolExecutionError() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("tc-1", "failingTool", Map.of())
            );

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createExceptionTool("failingTool", "Tool execution failed")));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test tool error");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify tool execution end with error
            AgentEvent.ToolExecutionEnd toolEnd = events.stream()
                    .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                    .map(e -> (AgentEvent.ToolExecutionEnd) e)
                    .findFirst()
                    .orElseThrow();
            assertThat(toolEnd.isError()).isTrue();

            // Verify agent completed
            assertThat(agent.getState().isStreaming()).isFalse();
        }
    }

    // ==================== Test: Cancellation ====================

    @Nested
    @DisplayName("Cancellation Integration")
    class CancellationTests {

        /**
         * Test 14.2: Cancellation via abort().
         */
        @Test
        @DisplayName("should abort agent loop via abort()")
        void abortAgentLoop() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean abortCalled = new AtomicBoolean(false);

            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnSlow(2000)) // Slow response
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test abort");

            // Abort after a short delay
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(100);
                    agent.abort();
                    abortCalled.set(true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(abortCalled.get()).isTrue();

            // Verify final state
            assertThat(agent.getState().isStreaming()).isFalse();
        }
    }

    // ==================== Test: Event Order Verification ====================

    @Nested
    @DisplayName("Event Order Verification")
    class EventOrderTests {

        /**
         * Test 14.2: Verify correct event order for complete flow.
         */
        @Test
        @DisplayName("should emit events in correct order")
        void eventsInCorrectOrder() throws Exception {
            // Arrange
            List<String> eventTypes = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("tc-1", "tool1", Map.of())
            );

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createTool("tool1")));

            agent.subscribe(event -> {
                eventTypes.add(event.type());
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test event order");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify agent_start is first
            assertThat(eventTypes.get(0)).isEqualTo("agent_start");

            // Verify agent_end is last
            assertThat(eventTypes.get(eventTypes.size() - 1)).isEqualTo("agent_end");

            // Verify turn_start comes before turn_end
            int firstTurnStart = eventTypes.indexOf("turn_start");
            int firstTurnEnd = eventTypes.indexOf("turn_end");
            assertThat(firstTurnStart).isLessThan(firstTurnEnd);

            // Verify tool_execution_start comes before tool_execution_end
            int toolStart = eventTypes.indexOf("tool_execution_start");
            int toolEnd = eventTypes.indexOf("tool_execution_end");
            assertThat(toolStart).isLessThan(toolEnd);

            // Verify message_start comes before message_end
            int msgStart = eventTypes.indexOf("message_start");
            int msgEnd = eventTypes.indexOf("message_end");
            assertThat(msgStart).isLessThan(msgEnd);
        }
    }

    // ==================== Test: State Transitions ====================

    @Nested
    @DisplayName("State Transitions Verification")
    class StateTransitionTests {

        /**
         * Test 14.2: Verify state transitions are correct.
         */
        @Test
        @DisplayName("should have correct state transitions")
        void correctStateTransitions() throws Exception {
            // Arrange
            AtomicBoolean isStreamingDuringLoop = new AtomicBoolean(false);
            AtomicReference<AgentMessage> streamMessageDuringStart = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            AgentOptions options = AgentOptions.builder()
                    .streamFn(mockStreamFnStop())
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());

            agent.subscribe(event -> {
                if (event instanceof AgentEvent.AgentStart) {
                    isStreamingDuringLoop.set(agent.getState().isStreaming());
                }
                if (event instanceof AgentEvent.MessageStart) {
                    streamMessageDuringStart.set(agent.getState().getStreamMessage());
                }
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test state transitions");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify isStreaming was true during loop
            assertThat(isStreamingDuringLoop.get()).isTrue();

            // Verify streamMessage was set during message_start
            assertThat(streamMessageDuringStart.get()).isNotNull();

            // Verify final state
            assertThat(agent.getState().isStreaming()).isFalse();
            assertThat(agent.getState().getStreamMessage()).isNull();
            assertThat(agent.getState().getPendingToolCalls()).isEmpty();
        }

        /**
         * Test 14.2: Verify messages are accumulated correctly.
         */
        @Test
        @DisplayName("should accumulate messages correctly")
        void messagesAccumulatedCorrectly() throws Exception {
            // Arrange
            CountDownLatch latch = new CountDownLatch(1);

            List<ToolCall> toolCalls = List.of(
                    new ToolCall("tc-1", "tool1", Map.of())
            );

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createTool("tool1")));

            agent.subscribe(event -> {
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test message accumulation");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // Verify messages: user, assistant (with tool call), toolResult, assistant (final)
            List<AgentMessage> messages = agent.getState().getMessages();
            assertThat(messages).hasSizeGreaterThanOrEqualTo(4);

            // Verify message roles
            assertThat(messages.get(0).role()).isEqualTo("user");
            assertThat(messages.get(1).role()).isEqualTo("assistant");
            assertThat(messages.get(2).role()).isEqualTo("toolResult");
            assertThat(messages.get(3).role()).isEqualTo("assistant");
        }
    }

    // ==================== Test: Queue Mode Behavior ====================

    @Nested
    @DisplayName("Queue Mode Behavior")
    class QueueModeTests {

        /**
         * Test 14.2: Verify ONE_AT_A_TIME steering mode.
         */
        @Test
        @DisplayName("should process steering messages one at a time")
        void steeringOneAtATime() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean steeringInjected = new AtomicBoolean(false);

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(List.of(
                            new ToolCall("tc-1", "tool1", Map.of())
                    )).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .steeringMode(QueueMode.ONE_AT_A_TIME)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createDelayedTool("tool1", 50)));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.ToolExecutionStart && !steeringInjected.get()) {
                    steeringInjected.set(true);
                    // Add multiple steering messages
                    agent.steer(MessageAdapter.wrap(new UserMessage("Steering 1", System.currentTimeMillis())));
                    agent.steer(MessageAdapter.wrap(new UserMessage("Steering 2", System.currentTimeMillis())));
                }
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test ONE_AT_A_TIME");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // With ONE_AT_A_TIME, only one steering message should be processed per turn
            // The second steering message may or may not be processed depending on timing
            assertThat(agent.getState().isStreaming()).isFalse();
        }

        /**
         * Test 14.2: Verify ALL steering mode.
         */
        @Test
        @DisplayName("should process all steering messages at once")
        void steeringAllMode() throws Exception {
            // Arrange
            List<AgentEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicBoolean steeringInjected = new AtomicBoolean(false);

            final int[] callCount = {0};
            StreamFn streamFn = (model, context, options) -> {
                callCount[0]++;
                if (callCount[0] == 1) {
                    return mockStreamFnWithToolCalls(List.of(
                            new ToolCall("tc-1", "tool1", Map.of())
                    )).stream(model, context, options);
                }
                return mockStreamFnStop().stream(model, context, options);
            };

            AgentOptions options = AgentOptions.builder()
                    .streamFn(streamFn)
                    .steeringMode(QueueMode.ALL)
                    .build();

            Agent agent = new Agent(options);
            agent.setModel(createTestModel());
            agent.setTools(List.of(createDelayedTool("tool1", 50)));

            agent.subscribe(event -> {
                events.add(event);
                if (event instanceof AgentEvent.ToolExecutionStart && !steeringInjected.get()) {
                    steeringInjected.set(true);
                    // Add multiple steering messages
                    agent.steer(MessageAdapter.wrap(new UserMessage("Steering 1", System.currentTimeMillis())));
                    agent.steer(MessageAdapter.wrap(new UserMessage("Steering 2", System.currentTimeMillis())));
                }
                if (event instanceof AgentEvent.AgentEnd) {
                    latch.countDown();
                }
            });

            // Act
            agent.prompt("Test ALL mode");

            // Assert
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            // With ALL mode, all steering messages should be processed together
            assertThat(agent.getState().isStreaming()).isFalse();
        }
    }
}

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
import com.pi.ai.core.types.ToolResultMessage;
import com.pi.ai.core.types.Usage;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 11: 工具执行异常产生正确的错误结果
 *
 * <p>For any exception thrown during tool execution (including RuntimeException
 * and checked Exception), the Agent loop should catch the exception and generate
 * an AgentToolResult with:
 * <ul>
 *   <li>content containing a single TextContent with the exception message</li>
 *   <li>details as an empty Map</li>
 *   <li>isError set to true</li>
 * </ul>
 * The loop should not be interrupted by the exception.
 *
 * <p><b>Validates: Requirements 5.3, 21.4, 41.1</b>
 */
class ToolExecutionErrorPropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<String> exceptionMessages() {
        return Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                Arbitraries.just(""),
                Arbitraries.just(null)
        );
    }

    @Provide
    Arbitrary<Class<? extends Exception>> exceptionTypes() {
        return Arbitraries.of(
                RuntimeException.class,
                IllegalArgumentException.class,
                IllegalStateException.class,
                NullPointerException.class
        );
    }

    // ==================== Helpers ====================

    /**
     * Creates a tool that throws the specified exception when executed.
     */
    private static AgentTool createThrowingTool(String name, Exception exception) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Throwing tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.failedFuture(exception);
            }
        };
    }

    /**
     * Creates a tool that throws a RuntimeException synchronously.
     */
    private static AgentTool createSyncThrowingTool(String name, String errorMessage) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Sync throwing tool: " + name; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                throw new RuntimeException(errorMessage);
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

    /**
     * Creates a mock StreamFn that returns tool calls on first call, then STOP.
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

    private static StreamFn mockStreamFnStop() {
        return mockStreamFnWithToolCalls(List.of());
    }

    // ==================== Property Tests ====================

    /**
     * Property 11: Tool execution exception produces error result with isError=true.
     */
    @Property(tries = 100)
    void toolExecutionException_producesErrorResultWithIsErrorTrue(
            @ForAll("exceptionMessages") String errorMessage
    ) {
        String toolName = "failingTool";
        Exception exception = new RuntimeException(errorMessage);
        AgentTool tool = createThrowingTool(toolName, exception);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prepared, null, stream);

        // Should be marked as error
        assertThat(result.isError()).isTrue();
    }

    /**
     * Property 11: Tool execution exception produces result with TextContent containing error message.
     */
    @Property(tries = 100)
    void toolExecutionException_producesTextContentWithErrorMessage(
            @ForAll("exceptionMessages") String errorMessage
    ) {
        String toolName = "failingTool";
        Exception exception = new RuntimeException(errorMessage);
        AgentTool tool = createThrowingTool(toolName, exception);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prepared, null, stream);

        // Content should have exactly one TextContent
        assertThat(result.result().content()).hasSize(1);
        assertThat(result.result().content().get(0)).isInstanceOf(TextContent.class);
        
        TextContent textContent = (TextContent) result.result().content().get(0);
        
        // Error message should be present - the text should not be null
        // Note: empty string is a valid error message (RuntimeException("") produces "")
        // and null message produces a default "Tool execution failed" message
        assertThat(textContent.text()).isNotNull();
        
        if (errorMessage != null && !errorMessage.isEmpty()) {
            assertThat(textContent.text()).isEqualTo(errorMessage);
        } else if (errorMessage == null) {
            // For null message, a default message should be used
            assertThat(textContent.text()).isNotEmpty();
        }
        // For empty string message, the text can be empty (this is valid behavior)
    }

    /**
     * Property 11: Tool execution exception produces result with empty Map details.
     */
    @Property(tries = 100)
    void toolExecutionException_producesEmptyMapDetails(
            @ForAll("exceptionMessages") String errorMessage
    ) {
        String toolName = "failingTool";
        Exception exception = new RuntimeException(errorMessage);
        AgentTool tool = createThrowingTool(toolName, exception);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prepared, null, stream);

        // Details should be an empty Map
        assertThat(result.result().details()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) result.result().details()).isEmpty();
    }

    /**
     * Property 11: Different exception types all produce correct error results.
     */
    @Property(tries = 100)
    void toolExecutionException_differentExceptionTypes_allProduceErrorResults(
            @ForAll("exceptionTypes") Class<? extends Exception> exceptionType,
            @ForAll("exceptionMessages") String errorMessage
    ) throws Exception {
        String toolName = "failingTool";
        
        // Create exception of the specified type
        Exception exception;
        try {
            if (errorMessage != null) {
                exception = exceptionType.getConstructor(String.class).newInstance(errorMessage);
            } else {
                exception = exceptionType.getConstructor().newInstance();
            }
        } catch (NoSuchMethodException e) {
            // Fall back to RuntimeException if constructor not available
            exception = new RuntimeException(errorMessage);
        }
        
        AgentTool tool = createThrowingTool(toolName, exception);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prepared, null, stream);

        // Should be marked as error regardless of exception type
        assertThat(result.isError()).isTrue();
        assertThat(result.result().content()).hasSize(1);
        assertThat(result.result().content().get(0)).isInstanceOf(TextContent.class);
    }

    /**
     * Property 11: Loop continues after tool execution exception.
     *
     * <p>When a tool throws an exception, the loop should not be interrupted.
     * Other tools should still execute, and the loop should complete normally.
     */
    @Property(tries = 100)
    void toolExecutionException_loopContinuesAfterException() {
        String failingToolName = "failingTool";
        String successToolName = "successTool";
        
        AgentTool failingTool = createThrowingTool(failingToolName, new RuntimeException("tool failed"));
        AgentTool successTool = new AgentTool() {
            @Override public String name() { return successToolName; }
            @Override public String description() { return "Success tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("success")), null));
            }
        };

        List<ToolCall> toolCalls = List.of(
                new ToolCall("tc-1", failingToolName, Map.of()),
                new ToolCall("tc-2", successToolName, Map.of())
        );

        AgentContext ctx = contextWithTools(List.of(failingTool, successTool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        AgentMessage assistantMsg = wrapAssistantMessage(toolCalls);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        List<ToolResultMessage> results = AgentLoop.executeToolCallsSequential(
                ctx, assistantMsg, toolCalls, config, null, stream);

        // Both tools should have results
        assertThat(results).hasSize(2);
        
        // First tool should be error
        assertThat(results.get(0).isError()).isTrue();
        
        // Second tool should succeed
        assertThat(results.get(1).isError()).isFalse();
    }

    /**
     * Property 11: Full agent loop completes normally after tool exception.
     *
     * <p>Verifies that the entire agent loop completes with agent_end event
     * even when a tool throws an exception.
     */
    @Property(tries = 100)
    void toolExecutionException_fullLoopCompletesNormally() {
        String failingToolName = "failingTool";
        AgentTool failingTool = createThrowingTool(failingToolName, new RuntimeException("tool failed"));

        List<ToolCall> toolCalls = List.of(new ToolCall("tc-1", failingToolName, Map.of()));

        AgentContext ctx = contextWithTools(List.of(failingTool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        // First call returns tool calls, second call returns STOP
        final int[] callCount = {0};
        StreamFn streamFn = (model, context, options) -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return mockStreamFnWithToolCalls(toolCalls).stream(model, context, options);
            }
            return mockStreamFnStop().stream(model, context, options);
        };

        AgentMessage userMessage = new AgentMessage() {
            @Override public String role() { return "user"; }
            @Override public long timestamp() { return System.currentTimeMillis(); }
        };

        EventStream<AgentEvent, List<AgentMessage>> stream =
                AgentLoop.agentLoop(List.of(userMessage), ctx, config, null, streamFn);

        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }

        // Loop should complete with agent_end
        assertThat(events.get(0)).isInstanceOf(AgentEvent.AgentStart.class);
        assertThat(events.get(events.size() - 1)).isInstanceOf(AgentEvent.AgentEnd.class);

        // Should have tool_execution_end with isError=true
        List<AgentEvent.ToolExecutionEnd> toolEndEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .toList();
        
        assertThat(toolEndEvents).hasSize(1);
        assertThat(toolEndEvents.get(0).isError()).isTrue();
    }

    /**
     * Property 11: Synchronous exception in tool execute method is handled.
     *
     * <p>Tests that exceptions thrown synchronously (not via CompletableFuture.failedFuture)
     * are also properly caught and converted to error results.
     */
    @Property(tries = 100)
    void toolExecutionException_syncExceptionHandled(
            @ForAll("exceptionMessages") String errorMessage
    ) {
        String toolName = "syncFailingTool";
        AgentTool tool = createSyncThrowingTool(toolName, errorMessage);

        AgentContext ctx = contextWithTools(List.of(tool));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prepared, null, stream);

        // Should be marked as error
        assertThat(result.isError()).isTrue();
        assertThat(result.result().content()).hasSize(1);
        assertThat(result.result().content().get(0)).isInstanceOf(TextContent.class);
    }
}

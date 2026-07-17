package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.ai.core.event.EventStream;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.TextContent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentLoop#executePreparedToolCall}.
 *
 * <p><b>Validates: Requirements 21.1, 21.2, 21.3, 21.4</b>
 */
class ExecutePreparedToolCallTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static AgentTool successTool(String name, AgentToolResult<?> result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Test tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.completedFuture(result);
            }
        };
    }

    private static AgentTool failingTool(String name, String errorMessage) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Failing tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.failedFuture(new RuntimeException(errorMessage));
            }
        };
    }

    private static AgentTool updatingTool(String name, AgentToolResult<?> finalResult,
                                          List<AgentToolResult<?>> updates) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Updating tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                for (AgentToolResult<?> update : updates) {
                    onUpdate.onUpdate(update);
                }
                return CompletableFuture.completedFuture(finalResult);
            }
        };
    }

    private static com.pi.ai.core.types.ToolCall toolCall(String id, String name) {
        return new com.pi.ai.core.types.ToolCall(id, name, Map.of());
    }

    private static com.pi.ai.core.types.ToolCall toolCall(String id, String name, Map<String, Object> args) {
        return new com.pi.ai.core.types.ToolCall(id, name, args);
    }

    private static PrepareResult.Prepared prepared(com.pi.ai.core.types.ToolCall tc, AgentTool tool) {
        return new PrepareResult.Prepared(tc, tool, tc.arguments());
    }

    private static EventStream<AgentEvent, List<AgentMessage>> createStream() {
        return AgentLoop.createAgentStream();
    }

    /**
     * Collects events from the stream after ending it.
     * Call this after executePreparedToolCall returns to drain pushed events.
     */
    private static List<AgentEvent> collectEvents(EventStream<AgentEvent, List<AgentMessage>> stream) {
        // End the stream with a result to place SENTINEL in queue, allowing iteration to complete
        stream.end(List.of());
        List<AgentEvent> events = new ArrayList<>();
        for (AgentEvent event : stream) {
            events.add(event);
        }
        return events;
    }

    // ── Successful execution (Req 21.1, 21.3) ──────────────────────────

    @Test
    void executePreparedToolCall_success_returnsResultWithIsErrorFalse() {
        AgentToolResult<?> expectedResult = new AgentToolResult<>(
                List.of(new TextContent("result")), Map.of("key", "value"));
        AgentTool tool = successTool("calc", expectedResult);
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-1", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(result.isError()).isFalse();
        assertThat(result.result()).isSameAs(expectedResult);
    }

    @Test
    void executePreparedToolCall_success_passesArgsAsJsonNode() {
        List<JsonNode> receivedArgs = new ArrayList<>();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "tracker"; }
            @Override public String description() { return "Tracks args"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                receivedArgs.add(args);
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("ok")), null));
            }
        };
        Map<String, Object> argsMap = Map.of("x", 42, "y", "hello");
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-2", "tracker", argsMap);
        PrepareResult.Prepared prep = new PrepareResult.Prepared(tc, tool, argsMap);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(receivedArgs).hasSize(1);
        JsonNode jsonArgs = receivedArgs.get(0);
        assertThat(jsonArgs.get("x").asInt()).isEqualTo(42);
        assertThat(jsonArgs.get("y").asText()).isEqualTo("hello");
    }

    @Test
    void executePreparedToolCall_success_passesToolCallId() {
        List<String> receivedIds = new ArrayList<>();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "idcheck"; }
            @Override public String description() { return "Checks id"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                receivedIds.add(toolCallId);
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("ok")), null));
            }
        };
        com.pi.ai.core.types.ToolCall tc = toolCall("my-unique-id", "idcheck");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(receivedIds).containsExactly("my-unique-id");
    }

    // ── onUpdate callback emits tool_execution_update (Req 21.2) ────────

    @Test
    void executePreparedToolCall_onUpdate_emitsToolExecutionUpdateEvents() {
        AgentToolResult<?> partial1 = new AgentToolResult<>(
                List.of(new TextContent("partial-1")), null);
        AgentToolResult<?> partial2 = new AgentToolResult<>(
                List.of(new TextContent("partial-2")), null);
        AgentToolResult<?> finalResult = new AgentToolResult<>(
                List.of(new TextContent("final")), null);

        AgentTool tool = updatingTool("streamer", finalResult, List.of(partial1, partial2));
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-upd", "streamer");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, null, stream);

        // Collect events by ending the stream
        List<AgentEvent> events = collectEvents(stream);

        List<AgentEvent.ToolExecutionUpdate> updates = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionUpdate)
                .map(e -> (AgentEvent.ToolExecutionUpdate) e)
                .toList();

        assertThat(updates).hasSize(2);
        assertThat(updates.get(0).toolCallId()).isEqualTo("tc-upd");
        assertThat(updates.get(0).toolName()).isEqualTo("streamer");
        assertThat(updates.get(0).partialResult()).isSameAs(partial1);
        assertThat(updates.get(1).partialResult()).isSameAs(partial2);
    }

    @Test
    void executePreparedToolCall_onUpdate_eventContainsCorrectArgs() {
        AgentToolResult<?> partial = new AgentToolResult<>(
                List.of(new TextContent("p")), null);
        AgentToolResult<?> finalResult = new AgentToolResult<>(
                List.of(new TextContent("done")), null);

        AgentTool tool = updatingTool("argtool", finalResult, List.of(partial));
        Map<String, Object> args = Map.of("query", "test");
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-args", "argtool", args);
        PrepareResult.Prepared prep = new PrepareResult.Prepared(tc, tool, args);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, null, stream);

        List<AgentEvent> events = collectEvents(stream);

        List<AgentEvent.ToolExecutionUpdate> updates = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionUpdate)
                .map(e -> (AgentEvent.ToolExecutionUpdate) e)
                .toList();

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).args()).isEqualTo(args);
    }

    // ── Exception handling (Req 21.4) ───────────────────────────────────

    @Test
    void executePreparedToolCall_exception_returnsErrorResultWithIsErrorTrue() {
        AgentTool tool = failingTool("broken", "something broke");
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-err", "broken");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(result.isError()).isTrue();
        assertThat(result.result().content()).hasSize(1);
        TextContent errorContent = (TextContent) result.result().content().get(0);
        assertThat(errorContent.text()).isEqualTo("something broke");
    }

    @Test
    void executePreparedToolCall_nullExceptionMessage_usesDefaultMessage() {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "nullmsg"; }
            @Override public String description() { return "Null msg tool"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                return CompletableFuture.failedFuture(new RuntimeException((String) null));
            }
        };
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-null", "nullmsg");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(result.isError()).isTrue();
        TextContent content = (TextContent) result.result().content().get(0);
        assertThat(content.text()).isNotNull();
        assertThat(content.text()).isNotEmpty();
    }

    @Test
    void executePreparedToolCall_errorResult_hasEmptyMapDetails() {
        AgentTool tool = failingTool("errdetails", "fail");
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-det", "errdetails");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ExecuteResult result = AgentLoop.executePreparedToolCall(prep, null, stream);

        assertThat(result.result().details()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) result.result().details()).isEmpty();
    }

    // ── Signal passthrough ──────────────────────────────────────────────

    @Test
    void executePreparedToolCall_passesSignalToTool() {
        List<CancellationSignal> receivedSignals = new ArrayList<>();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "sigcheck"; }
            @Override public String description() { return "Signal check"; }
            @Override public JsonNode parameters() { return null; }
            @Override public CompletableFuture<AgentToolResult<?>> execute(
                    String toolCallId, JsonNode args, CancellationSignal signal,
                    AgentToolUpdateCallback onUpdate) {
                receivedSignals.add(signal);
                return CompletableFuture.completedFuture(
                        new AgentToolResult<>(List.of(new TextContent("ok")), null));
            }
        };
        CancellationSignal signal = new CancellationSignal();
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-sig", "sigcheck");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, signal, stream);

        assertThat(receivedSignals).hasSize(1);
        assertThat(receivedSignals.get(0)).isSameAs(signal);
    }

    // ── No updates scenario ─────────────────────────────────────────────

    @Test
    void executePreparedToolCall_noUpdates_noUpdateEventsEmitted() {
        AgentToolResult<?> finalResult = new AgentToolResult<>(
                List.of(new TextContent("done")), null);
        AgentTool tool = successTool("quiet", finalResult);
        com.pi.ai.core.types.ToolCall tc = toolCall("tc-quiet", "quiet");
        PrepareResult.Prepared prep = prepared(tc, tool);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.executePreparedToolCall(prep, null, stream);

        List<AgentEvent> events = collectEvents(stream);

        List<AgentEvent.ToolExecutionUpdate> updates = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionUpdate)
                .map(e -> (AgentEvent.ToolExecutionUpdate) e)
                .toList();

        assertThat(updates).isEmpty();
    }
}

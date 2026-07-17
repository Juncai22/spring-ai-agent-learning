package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.AfterToolCallResult;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.event.EventStream;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.ToolResultMessage;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.types.UserContentBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentLoop#finalizeExecutedToolCall}.
 *
 * <p><b>Validates: Requirements 22.1, 22.2, 22.3, 22.4, 22.5</b>
 */
class FinalizeExecutedToolCallTest {

    // ── Helpers ──────────────────────────────────────────────────────────

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

    private static AgentMessage wrapAssistantMessage() {
        AssistantMessage msg = AssistantMessage.builder()
                .content(List.of())
                .stopReason(StopReason.STOP)
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

    private static PrepareResult.Prepared prepared(ToolCall tc, AgentTool tool) {
        return new PrepareResult.Prepared(tc, tool, tc.arguments());
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

    // ── Basic finalization (Req 22.3, 22.4, 22.5) ────────────────────────

    @Test
    void finalizeExecutedToolCall_emitsToolExecutionEndEvent() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        ToolCall tc = toolCall("tc-1", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> result = new AgentToolResult<>(
                List.of(new TextContent("42")), Map.of("computed", true));
        ExecuteResult executed = new ExecuteResult(result, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.finalizeExecutedToolCall(ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        List<AgentEvent> events = collectEvents(stream);
        List<AgentEvent.ToolExecutionEnd> endEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .toList();

        assertThat(endEvents).hasSize(1);
        assertThat(endEvents.get(0).toolCallId()).isEqualTo("tc-1");
        assertThat(endEvents.get(0).toolName()).isEqualTo("calc");
        assertThat(endEvents.get(0).result()).isSameAs(result);
        assertThat(endEvents.get(0).isError()).isFalse();
    }

    @Test
    void finalizeExecutedToolCall_emitsMessageStartAndEndEvents() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        ToolCall tc = toolCall("tc-2", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> result = new AgentToolResult<>(
                List.of(new TextContent("result")), null);
        ExecuteResult executed = new ExecuteResult(result, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        AgentLoop.finalizeExecutedToolCall(ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        List<AgentEvent> events = collectEvents(stream);
        List<AgentEvent.MessageStart> startEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageStart)
                .map(e -> (AgentEvent.MessageStart) e)
                .toList();
        List<AgentEvent.MessageEnd> endMsgEvents = events.stream()
                .filter(e -> e instanceof AgentEvent.MessageEnd)
                .map(e -> (AgentEvent.MessageEnd) e)
                .toList();

        assertThat(startEvents).hasSize(1);
        assertThat(endMsgEvents).hasSize(1);
    }

    @Test
    void finalizeExecutedToolCall_returnsToolResultMessage() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        ToolCall tc = toolCall("tc-3", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> result = new AgentToolResult<>(
                List.of(new TextContent("answer")), Map.of("key", "value"));
        ExecuteResult executed = new ExecuteResult(result, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        assertThat(toolResultMsg.toolCallId()).isEqualTo("tc-3");
        assertThat(toolResultMsg.toolName()).isEqualTo("calc");
        assertThat(toolResultMsg.content()).isEqualTo(result.content());
        assertThat(toolResultMsg.details()).isEqualTo(result.details());
        assertThat(toolResultMsg.isError()).isFalse();
    }

    @Test
    void finalizeExecutedToolCall_errorResult_setsIsErrorTrue() {
        AgentTool tool = createTool("broken");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        ToolCall tc = toolCall("tc-err", "broken");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> errorResult = new AgentToolResult<>(
                List.of(new TextContent("error occurred")), Map.of());
        ExecuteResult executed = new ExecuteResult(errorResult, true);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        assertThat(toolResultMsg.isError()).isTrue();

        List<AgentEvent> events = collectEvents(stream);
        AgentEvent.ToolExecutionEnd endEvent = events.stream()
                .filter(e -> e instanceof AgentEvent.ToolExecutionEnd)
                .map(e -> (AgentEvent.ToolExecutionEnd) e)
                .findFirst().orElseThrow();
        assertThat(endEvent.isError()).isTrue();
    }

    // ── AfterToolCallHook (Req 22.1, 22.2) ───────────────────────────────

    @Test
    void finalizeExecutedToolCall_afterHookOverridesContent() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        List<UserContentBlock> newContent = List.of(new TextContent("modified result"));
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(newContent, null, null)))
                .build();
        ToolCall tc = toolCall("tc-hook", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("orig", true));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        // Content should be overridden
        assertThat(toolResultMsg.content()).isEqualTo(newContent);
        // Details should be preserved
        assertThat(toolResultMsg.details()).isEqualTo(Map.of("orig", true));
    }

    @Test
    void finalizeExecutedToolCall_afterHookOverridesDetails() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        Map<String, Object> newDetails = Map.of("new", "details");
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(null, newDetails, null)))
                .build();
        ToolCall tc = toolCall("tc-det", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("old", "details"));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        // Content should be preserved
        assertThat(toolResultMsg.content()).isEqualTo(originalResult.content());
        // Details should be overridden
        assertThat(toolResultMsg.details()).isEqualTo(newDetails);
    }

    @Test
    void finalizeExecutedToolCall_afterHookOverridesIsError() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(null, null, true)))
                .build();
        ToolCall tc = toolCall("tc-err-override", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("result")), null);
        ExecuteResult executed = new ExecuteResult(originalResult, false); // originally not error
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        // isError should be overridden to true
        assertThat(toolResultMsg.isError()).isTrue();
    }

    @Test
    void finalizeExecutedToolCall_afterHookReturnsNull_preservesOriginal() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(null))
                .build();
        ToolCall tc = toolCall("tc-null", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("key", "value"));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        assertThat(toolResultMsg.content()).isEqualTo(originalResult.content());
        assertThat(toolResultMsg.details()).isEqualTo(originalResult.details());
        assertThat(toolResultMsg.isError()).isFalse();
    }

    @Test
    void finalizeExecutedToolCall_afterHookThrows_preservesOriginal() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.failedFuture(new RuntimeException("hook failed")))
                .build();
        ToolCall tc = toolCall("tc-throw", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("key", "value"));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        // Should preserve original on hook failure
        assertThat(toolResultMsg.content()).isEqualTo(originalResult.content());
        assertThat(toolResultMsg.details()).isEqualTo(originalResult.details());
        assertThat(toolResultMsg.isError()).isFalse();
    }

    @Test
    void finalizeExecutedToolCall_noAfterHook_preservesOriginal() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build(); // no afterToolCall
        ToolCall tc = toolCall("tc-no-hook", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("key", "value"));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        assertThat(toolResultMsg.content()).isEqualTo(originalResult.content());
        assertThat(toolResultMsg.details()).isEqualTo(originalResult.details());
        assertThat(toolResultMsg.isError()).isFalse();
    }

    @Test
    void finalizeExecutedToolCall_afterHookOverridesAllFields() {
        AgentTool tool = createTool("calc");
        AgentContext ctx = contextWithTools(tool);
        List<UserContentBlock> newContent = List.of(new TextContent("new content"));
        Map<String, Object> newDetails = Map.of("new", "details");
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(newContent, newDetails, true)))
                .build();
        ToolCall tc = toolCall("tc-all", "calc");
        PrepareResult.Prepared prep = prepared(tc, tool);
        AgentToolResult<?> originalResult = new AgentToolResult<>(
                List.of(new TextContent("original")), Map.of("old", "details"));
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage toolResultMsg = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(), prep, executed, config, null, stream);

        assertThat(toolResultMsg.content()).isEqualTo(newContent);
        assertThat(toolResultMsg.details()).isEqualTo(newDetails);
        assertThat(toolResultMsg.isError()).isTrue();
    }
}

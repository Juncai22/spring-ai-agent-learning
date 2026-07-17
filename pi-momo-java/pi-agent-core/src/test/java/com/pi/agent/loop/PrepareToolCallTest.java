package com.pi.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.agent.config.AgentLoopConfig;
import com.pi.agent.types.AgentContext;
import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentTool;
import com.pi.agent.types.AgentToolResult;
import com.pi.agent.types.AgentToolUpdateCallback;
import com.pi.agent.types.BeforeToolCallResult;
import com.pi.agent.types.MessageAdapter;
import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.CancellationSignal;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.util.PiAiJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AgentLoop#prepareToolCall} and {@link AgentLoop#createErrorToolResult}.
 *
 * <p><b>Validates: Requirements 20.1, 20.2, 20.3, 20.4, 20.5, 20.6, 20.7</b>
 */
class PrepareToolCallTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private static AgentTool createTool(String name) {
        return createTool(name, null);
    }

    private static AgentTool createTool(String name, JsonNode parameters) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "Test tool: " + name; }
            @Override public JsonNode parameters() { return parameters; }
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

    private static ToolCall toolCall(String name) {
        return new ToolCall("tc-1", name, Map.of());
    }

    private static ToolCall toolCall(String name, Map<String, Object> args) {
        return new ToolCall("tc-1", name, args);
    }

    private static AgentContext contextWithTools(AgentTool... tools) {
        return AgentContext.builder()
                .systemPrompt("test")
                .tools(List.of(tools))
                .build();
    }

    // ── createErrorToolResult ────────────────────────────────────────────

    @Test
    void createErrorToolResult_containsTextContentWithMessage() {
        AgentToolResult<?> result = AgentLoop.createErrorToolResult("something went wrong");

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(TextContent.class);
        TextContent tc = (TextContent) result.content().get(0);
        assertThat(tc.text()).isEqualTo("something went wrong");
    }

    @Test
    void createErrorToolResult_hasEmptyMapDetails() {
        AgentToolResult<?> result = AgentLoop.createErrorToolResult("error");

        assertThat(result.details()).isInstanceOf(Map.class);
        assertThat((Map<?, ?>) result.details()).isEmpty();
    }

    // ── prepareToolCall: tool not found ──────────────────────────────────

    @Test
    void prepareToolCall_toolNotFound_returnsImmediate() {
        AgentContext ctx = contextWithTools(createTool("calculator"));
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("nonexistent"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        assertThat(immediate.isError()).isTrue();

        TextContent tc = (TextContent) immediate.result().content().get(0);
        assertThat(tc.text()).contains("nonexistent").contains("not found");
    }

    @Test
    void prepareToolCall_nullToolsList_returnsImmediate() {
        AgentContext ctx = AgentContext.builder().systemPrompt("test").build();
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("any"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
    }

    // ── prepareToolCall: validation failure ──────────────────────────────

    @Test
    void prepareToolCall_validationFailure_returnsImmediate() {
        // Create a tool with a strict JSON schema requiring a "query" string field
        JsonNode schema = PiAiJson.MAPPER.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")
        ));
        AgentTool tool = createTool("search", schema);
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        // Call with missing required "query" argument
        ToolCall tc = toolCall("search", Map.of());

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), tc, config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        assertThat(immediate.isError()).isTrue();
    }

    // ── prepareToolCall: BeforeToolCallHook blocks ───────────────────────

    @Test
    void prepareToolCall_hookBlocksWithReason_returnsImmediateWithReason() {
        AgentTool tool = createTool("dangerous");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, "Not allowed")))
                .build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("dangerous"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        assertThat(immediate.isError()).isTrue();

        TextContent tc = (TextContent) immediate.result().content().get(0);
        assertThat(tc.text()).isEqualTo("Not allowed");
    }

    @Test
    void prepareToolCall_hookBlocksWithNullReason_usesDefaultMessage() {
        AgentTool tool = createTool("dangerous");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(true, null)))
                .build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("dangerous"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        TextContent tc = (TextContent) immediate.result().content().get(0);
        assertThat(tc.text()).isEqualTo("Tool execution was blocked");
    }

    @Test
    void prepareToolCall_hookAllows_returnsPrepared() {
        AgentTool tool = createTool("safe");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new BeforeToolCallResult(false, null)))
                .build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("safe"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
    }

    @Test
    void prepareToolCall_hookReturnsNull_returnsPrepared() {
        AgentTool tool = createTool("safe");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.completedFuture(null))
                .build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("safe"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
    }

    // ── prepareToolCall: all checks pass ─────────────────────────────────

    @Test
    void prepareToolCall_allChecksPass_returnsPrepared() {
        AgentTool tool = createTool("calculator");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("calculator"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
        PrepareResult.Prepared prepared = (PrepareResult.Prepared) result;
        assertThat(prepared.tool()).isSameAs(tool);
        assertThat(prepared.toolCall().name()).isEqualTo("calculator");
    }

    @Test
    void prepareToolCall_preparedResultContainsArgs() {
        AgentTool tool = createTool("calculator");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();
        Map<String, Object> args = Map.of("x", 1, "y", 2);

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("calculator", args), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
        PrepareResult.Prepared prepared = (PrepareResult.Prepared) result;
        assertThat(prepared.args()).isEqualTo(args);
    }

    @Test
    void prepareToolCall_noHookConfigured_returnsPrepared() {
        AgentTool tool = createTool("simple");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder().build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("simple"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Prepared.class);
    }

    @Test
    void prepareToolCall_hookThrowsException_returnsImmediate() {
        AgentTool tool = createTool("flaky");
        AgentContext ctx = contextWithTools(tool);
        AgentLoopConfig config = AgentLoopConfig.builder()
                .beforeToolCall((context, signal) ->
                        CompletableFuture.failedFuture(new RuntimeException("hook crashed")))
                .build();

        PrepareResult result = AgentLoop.prepareToolCall(
                ctx, wrapAssistantMessage(), toolCall("flaky"), config, null);

        assertThat(result).isInstanceOf(PrepareResult.Immediate.class);
        PrepareResult.Immediate immediate = (PrepareResult.Immediate) result;
        assertThat(immediate.isError()).isTrue();
    }
}

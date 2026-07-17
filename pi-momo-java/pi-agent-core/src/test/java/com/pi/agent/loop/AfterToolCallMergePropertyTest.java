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
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 7: AfterToolCall 字段级合并语义
 *
 * <p>For any tool execution result and for any AfterToolCallResult override values,
 * the merged final result should satisfy:
 * <ul>
 *   <li>For non-null fields in AfterToolCallResult (content, details, isError),
 *       the final result uses the override value</li>
 *   <li>For null fields, the final result preserves the original tool execution result value</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 3.5, 3.6, 22.2</b>
 */
class AfterToolCallMergePropertyTest {

    // ==================== Generators ====================

    @Provide
    Arbitrary<List<UserContentBlock>> optionalContent() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(text -> List.<UserContentBlock>of(new TextContent(text)))
        );
    }

    @Provide
    Arbitrary<Object> optionalDetails() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
                        .map(key -> Map.of(key, "value"))
        );
    }

    @Provide
    Arbitrary<Boolean> optionalIsError() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE)
        );
    }

    // ==================== Helpers ====================

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

    private static AgentMessage wrapAssistantMessage(ToolCall toolCall) {
        List<com.pi.ai.core.types.AssistantContentBlock> content = List.of(toolCall);
        AssistantMessage msg = AssistantMessage.builder()
                .content(content)
                .stopReason(StopReason.TOOL_USE)
                .usage(new Usage(0, 0, 0, 0, 0, null))
                .timestamp(System.currentTimeMillis())
                .build();
        return MessageAdapter.wrap(msg);
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

    // ==================== Property Tests ====================

    /**
     * Property 7: AfterToolCall field-level merge - content override.
     *
     * <p>When AfterToolCallResult.content is non-null, it should override the original content.
     * When null, the original content should be preserved.
     */
    @Property(tries = 100)
    void afterToolCallMerge_contentOverrideOrPreserve(
            @ForAll("optionalContent") List<UserContentBlock> overrideContent
    ) {
        // Original tool result
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        Map<String, Object> originalDetails = Map.of("original", "details");
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, originalDetails);
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(overrideContent, null, null)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        if (overrideContent != null) {
            // Content should be overridden
            assertThat(result.content()).isEqualTo(overrideContent);
        } else {
            // Content should be preserved
            assertThat(result.content()).isEqualTo(originalContent);
        }
        
        // Details should always be preserved (not overridden in this test)
        assertThat(result.details()).isEqualTo(originalDetails);
    }

    /**
     * Property 7: AfterToolCall field-level merge - details override.
     *
     * <p>When AfterToolCallResult.details is non-null, it should override the original details.
     * When null, the original details should be preserved.
     */
    @Property(tries = 100)
    void afterToolCallMerge_detailsOverrideOrPreserve(
            @ForAll("optionalDetails") Object overrideDetails
    ) {
        // Original tool result
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        Map<String, Object> originalDetails = Map.of("original", "details");
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, originalDetails);
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(null, overrideDetails, null)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, false);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        // Content should always be preserved (not overridden in this test)
        assertThat(result.content()).isEqualTo(originalContent);
        
        if (overrideDetails != null) {
            // Details should be overridden
            assertThat(result.details()).isEqualTo(overrideDetails);
        } else {
            // Details should be preserved
            assertThat(result.details()).isEqualTo(originalDetails);
        }
    }

    /**
     * Property 7: AfterToolCall field-level merge - isError override.
     *
     * <p>When AfterToolCallResult.isError is non-null, it should override the original isError.
     * When null, the original isError should be preserved.
     */
    @Property(tries = 100)
    void afterToolCallMerge_isErrorOverrideOrPreserve(
            @ForAll("optionalIsError") Boolean overrideIsError
    ) {
        // Original tool result (not an error)
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, null);
        boolean originalIsError = false;
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(null, null, overrideIsError)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, originalIsError);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        if (overrideIsError != null) {
            // isError should be overridden
            assertThat(result.isError()).isEqualTo(overrideIsError);
        } else {
            // isError should be preserved
            assertThat(result.isError()).isEqualTo(originalIsError);
        }
    }

    /**
     * Property 7: AfterToolCall field-level merge - all fields combined.
     *
     * <p>Tests all three fields together with random combinations of null/non-null values.
     */
    @Property(tries = 100)
    void afterToolCallMerge_allFieldsCombined(
            @ForAll("optionalContent") List<UserContentBlock> overrideContent,
            @ForAll("optionalDetails") Object overrideDetails,
            @ForAll("optionalIsError") Boolean overrideIsError
    ) {
        // Original tool result
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        Map<String, Object> originalDetails = Map.of("original", "details");
        boolean originalIsError = false;
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, originalDetails);
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(overrideContent, overrideDetails, overrideIsError)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, originalIsError);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        // Verify content
        if (overrideContent != null) {
            assertThat(result.content()).isEqualTo(overrideContent);
        } else {
            assertThat(result.content()).isEqualTo(originalContent);
        }

        // Verify details
        if (overrideDetails != null) {
            assertThat(result.details()).isEqualTo(overrideDetails);
        } else {
            assertThat(result.details()).isEqualTo(originalDetails);
        }

        // Verify isError
        if (overrideIsError != null) {
            assertThat(result.isError()).isEqualTo(overrideIsError);
        } else {
            assertThat(result.isError()).isEqualTo(originalIsError);
        }
    }

    /**
     * Property 7: AfterToolCall null result preserves all original values.
     *
     * <p>When AfterToolCallHook returns null, all original values should be preserved.
     */
    @Property(tries = 100)
    void afterToolCallMerge_nullResultPreservesOriginal() {
        // Original tool result
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        Map<String, Object> originalDetails = Map.of("original", "details");
        boolean originalIsError = false;
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, originalDetails);
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(null))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, originalIsError);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        // All values should be preserved
        assertThat(result.content()).isEqualTo(originalContent);
        assertThat(result.details()).isEqualTo(originalDetails);
        assertThat(result.isError()).isEqualTo(originalIsError);
    }

    /**
     * Property 7: AfterToolCall with all null fields preserves all original values.
     *
     * <p>When AfterToolCallResult has all null fields, all original values should be preserved.
     */
    @Property(tries = 100)
    void afterToolCallMerge_allNullFieldsPreservesOriginal() {
        // Original tool result
        List<UserContentBlock> originalContent = List.of(new TextContent("original content"));
        Map<String, Object> originalDetails = Map.of("original", "details");
        boolean originalIsError = true; // Test with error=true
        AgentToolResult<?> originalResult = new AgentToolResult<>(originalContent, originalDetails);
        
        String toolName = "testTool";
        AgentTool tool = createTool(toolName, originalResult);
        AgentContext ctx = contextWithTools(tool);
        
        AgentLoopConfig config = AgentLoopConfig.builder()
                .afterToolCall((context, signal) ->
                        CompletableFuture.completedFuture(
                                new AfterToolCallResult(null, null, null)))
                .build();

        ToolCall toolCall = new ToolCall("tc-1", toolName, Map.of());
        PrepareResult.Prepared prepared = new PrepareResult.Prepared(toolCall, tool, toolCall.arguments());
        ExecuteResult executed = new ExecuteResult(originalResult, originalIsError);
        EventStream<AgentEvent, List<AgentMessage>> stream = createStream();

        ToolResultMessage result = AgentLoop.finalizeExecutedToolCall(
                ctx, wrapAssistantMessage(toolCall), prepared, executed, config, null, stream);

        // All values should be preserved
        assertThat(result.content()).isEqualTo(originalContent);
        assertThat(result.details()).isEqualTo(originalDetails);
        assertThat(result.isError()).isEqualTo(originalIsError);
    }
}

package com.pi.agent.proxy;

import com.pi.agent.event.ProxyAssistantMessageEvent;
import com.pi.ai.core.types.*;
import net.jqwik.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 10: ProxyStream partial 消息重建正确性
 *
 * <p>For any valid ProxyAssistantMessageEvent sequence (starting with Start, ending with Done or Error,
 * with any combination of text/thinking/toolcall start/delta/end events in between),
 * after processing each event via {@code processProxyEvent}, the reconstructed partial AssistantMessage should satisfy:
 * <ul>
 *   <li>All text_delta concatenations equal the corresponding TextContent.text field</li>
 *   <li>All thinking_delta concatenations equal the corresponding ThinkingContent.thinking field</li>
 *   <li>All toolcall_delta concatenations, when JSON parsed, equal the corresponding ToolCall.arguments field</li>
 *   <li>contentSignature is correctly set on the corresponding content blocks</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 36.5, 36.6, 36.7, 36.8, 36.9, 36.14, 36.15</b>
 */
class ProxyStreamReconstructionPropertyTest {

    // ==================== Primitive generators ====================

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(30)
                .alpha()
                .numeric();
    }

    @Provide
    Arbitrary<String> contentSignatures() {
        return Arbitraries.strings()
                .ofMinLength(5)
                .ofMaxLength(20)
                .alpha()
                .numeric()
                .injectNull(0.3);
    }

    @Provide
    Arbitrary<String> toolIds() {
        return Arbitraries.strings()
                .ofMinLength(5)
                .ofMaxLength(15)
                .alpha()
                .numeric()
                .map(s -> "tool_" + s);
    }

    @Provide
    Arbitrary<String> toolNames() {
        return Arbitraries.of("readFile", "writeFile", "executeCommand", "searchCode", "listDirectory");
    }

    // ==================== Content block specification ====================

    /**
     * Specification for a text content block to be generated.
     */
    record TextBlockSpec(List<String> deltas, String contentSignature) {
        String expectedText() {
            return String.join("", deltas);
        }
    }

    /**
     * Specification for a thinking content block to be generated.
     */
    record ThinkingBlockSpec(List<String> deltas, String contentSignature) {
        String expectedThinking() {
            return String.join("", deltas);
        }
    }

    /**
     * Specification for a tool call content block to be generated.
     */
    record ToolCallBlockSpec(String id, String toolName, Map<String, Object> arguments) {
        /**
         * Split the JSON arguments into chunks for streaming simulation.
         */
        List<String> toJsonDeltas() {
            String json = toJson(arguments);
            return splitIntoChunks(json);
        }

        private static String toJson(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String) {
                    sb.append("\"").append(value).append("\"");
                } else if (value instanceof Number) {
                    sb.append(value);
                } else if (value instanceof Boolean) {
                    sb.append(value);
                } else {
                    sb.append("\"").append(value).append("\"");
                }
            }
            sb.append("}");
            return sb.toString();
        }

        private static List<String> splitIntoChunks(String json) {
            List<String> chunks = new ArrayList<>();
            if (json.length() <= 3) {
                chunks.add(json);
                return chunks;
            }
            // Split into 2-4 chunks
            int chunkSize = Math.max(1, json.length() / 3);
            int pos = 0;
            while (pos < json.length()) {
                int end = Math.min(pos + chunkSize, json.length());
                chunks.add(json.substring(pos, end));
                pos = end;
            }
            return chunks;
        }
    }

    /**
     * Sealed interface for content block specifications.
     */
    sealed interface ContentBlockSpec permits TextBlockSpecWrapper, ThinkingBlockSpecWrapper, ToolCallBlockSpecWrapper {}

    record TextBlockSpecWrapper(TextBlockSpec spec) implements ContentBlockSpec {}
    record ThinkingBlockSpecWrapper(ThinkingBlockSpec spec) implements ContentBlockSpec {}
    record ToolCallBlockSpecWrapper(ToolCallBlockSpec spec) implements ContentBlockSpec {}

    /**
     * Complete event sequence specification.
     */
    record EventSequenceSpec(
            List<ContentBlockSpec> contentBlocks,
            boolean endsWithError,
            StopReason stopReason,
            Usage usage,
            String errorMessage
    ) {}

    // ==================== Content block generators ====================

    @Provide
    Arbitrary<TextBlockSpec> textBlockSpecs() {
        return Combinators.combine(
                safeStrings().list().ofMinSize(1).ofMaxSize(5),
                contentSignatures()
        ).as(TextBlockSpec::new);
    }

    @Provide
    Arbitrary<ThinkingBlockSpec> thinkingBlockSpecs() {
        return Combinators.combine(
                safeStrings().list().ofMinSize(1).ofMaxSize(5),
                contentSignatures()
        ).as(ThinkingBlockSpec::new);
    }

    @Provide
    Arbitrary<Map<String, Object>> toolArguments() {
        return Combinators.combine(
                safeStrings(),
                Arbitraries.integers().between(1, 100),
                Arbitraries.of(true, false)
        ).as((strVal, intVal, boolVal) -> {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("path", strVal);
            args.put("count", intVal);
            args.put("recursive", boolVal);
            return args;
        });
    }

    @Provide
    Arbitrary<ToolCallBlockSpec> toolCallBlockSpecs() {
        return Combinators.combine(toolIds(), toolNames(), toolArguments())
                .as(ToolCallBlockSpec::new);
    }

    @Provide
    Arbitrary<ContentBlockSpec> contentBlockSpecs() {
        return Arbitraries.oneOf(
                textBlockSpecs().map(TextBlockSpecWrapper::new),
                thinkingBlockSpecs().map(ThinkingBlockSpecWrapper::new),
                toolCallBlockSpecs().map(ToolCallBlockSpecWrapper::new)
        );
    }

    @Provide
    Arbitrary<Usage> usages() {
        return Combinators.combine(
                Arbitraries.integers().between(10, 1000),
                Arbitraries.integers().between(10, 500),
                Arbitraries.integers().between(0, 100),
                Arbitraries.integers().between(0, 100)
        ).as((input, output, cacheRead, cacheWrite) ->
                new Usage(input, output, cacheRead, cacheWrite,
                        input + output,
                        new Usage.Cost(0.001, 0.002, 0.0, 0.0, 0.003)));
    }

    @Provide
    Arbitrary<EventSequenceSpec> eventSequenceSpecs() {
        return Combinators.combine(
                contentBlockSpecs().list().ofMinSize(1).ofMaxSize(5),
                Arbitraries.of(true, false),
                Arbitraries.of(StopReason.STOP, StopReason.TOOL_USE, StopReason.LENGTH),
                usages(),
                safeStrings()
        ).as(EventSequenceSpec::new);
    }

    // ==================== Event sequence builder ====================

    /**
     * Build a list of ProxyAssistantMessageEvent from the specification.
     */
    private List<ProxyAssistantMessageEvent> buildEventSequence(EventSequenceSpec spec) {
        List<ProxyAssistantMessageEvent> events = new ArrayList<>();

        // Start event
        events.add(new ProxyAssistantMessageEvent.Start());

        // Content block events
        int contentIndex = 0;
        for (ContentBlockSpec blockSpec : spec.contentBlocks()) {
            if (blockSpec instanceof TextBlockSpecWrapper wrapper) {
                TextBlockSpec textSpec = wrapper.spec();
                events.add(new ProxyAssistantMessageEvent.TextStart(contentIndex));
                for (String delta : textSpec.deltas()) {
                    events.add(new ProxyAssistantMessageEvent.TextDelta(contentIndex, delta));
                }
                events.add(new ProxyAssistantMessageEvent.TextEnd(contentIndex, textSpec.contentSignature()));

            } else if (blockSpec instanceof ThinkingBlockSpecWrapper wrapper) {
                ThinkingBlockSpec thinkingSpec = wrapper.spec();
                events.add(new ProxyAssistantMessageEvent.ThinkingStart(contentIndex));
                for (String delta : thinkingSpec.deltas()) {
                    events.add(new ProxyAssistantMessageEvent.ThinkingDelta(contentIndex, delta));
                }
                events.add(new ProxyAssistantMessageEvent.ThinkingEnd(contentIndex, thinkingSpec.contentSignature()));

            } else if (blockSpec instanceof ToolCallBlockSpecWrapper wrapper) {
                ToolCallBlockSpec toolSpec = wrapper.spec();
                events.add(new ProxyAssistantMessageEvent.ToolCallStart(contentIndex, toolSpec.id(), toolSpec.toolName()));
                for (String delta : toolSpec.toJsonDeltas()) {
                    events.add(new ProxyAssistantMessageEvent.ToolCallDelta(contentIndex, delta));
                }
                events.add(new ProxyAssistantMessageEvent.ToolCallEnd(contentIndex));
            }
            contentIndex++;
        }

        // Terminal event
        if (spec.endsWithError()) {
            events.add(new ProxyAssistantMessageEvent.Error(StopReason.ERROR, spec.errorMessage(), spec.usage()));
        } else {
            events.add(new ProxyAssistantMessageEvent.Done(spec.stopReason(), spec.usage()));
        }

        return events;
    }

    // ==================== Property tests ====================

    /**
     * Property 10: ProxyStream partial message reconstruction correctness.
     *
     * <p>Verifies that processing a valid event sequence correctly reconstructs:
     * <ul>
     *   <li>Text content from text_delta concatenation</li>
     *   <li>Thinking content from thinking_delta concatenation</li>
     *   <li>Tool call arguments from toolcall_delta JSON parsing</li>
     *   <li>Content signatures on corresponding blocks</li>
     * </ul>
     *
     * <p><b>Validates: Requirements 36.5, 36.6, 36.7, 36.8, 36.9, 36.14, 36.15</b>
     */
    @Property(tries = 200)
    void proxyStreamReconstructsPartialMessageCorrectly(
            @ForAll("eventSequenceSpecs") EventSequenceSpec spec
    ) {
        // Build event sequence
        List<ProxyAssistantMessageEvent> events = buildEventSequence(spec);

        // Initialize partial message
        AssistantMessage partial = AssistantMessage.builder()
                .content(new ArrayList<>())
                .api("test-api")
                .provider("test-provider")
                .model("test-model")
                .usage(new Usage(0, 0, 0, 0, 0, new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();

        Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

        // Process all events
        for (ProxyAssistantMessageEvent event : events) {
            ProxyStream.processProxyEvent(event, partial, partialJsonMap);
        }

        // Verify content blocks
        assertThat(partial.getContent()).hasSize(spec.contentBlocks().size());

        for (int i = 0; i < spec.contentBlocks().size(); i++) {
            ContentBlockSpec blockSpec = spec.contentBlocks().get(i);
            AssistantContentBlock content = partial.getContent().get(i);

            if (blockSpec instanceof TextBlockSpecWrapper wrapper) {
                TextBlockSpec textSpec = wrapper.spec();
                assertThat(content).isInstanceOf(TextContent.class);
                TextContent textContent = (TextContent) content;

                // Verify text concatenation (Req 36.5)
                assertThat(textContent.text()).isEqualTo(textSpec.expectedText());

                // Verify contentSignature (Req 36.14)
                assertThat(textContent.textSignature()).isEqualTo(textSpec.contentSignature());

            } else if (blockSpec instanceof ThinkingBlockSpecWrapper wrapper) {
                ThinkingBlockSpec thinkingSpec = wrapper.spec();
                assertThat(content).isInstanceOf(ThinkingContent.class);
                ThinkingContent thinkingContent = (ThinkingContent) content;

                // Verify thinking concatenation (Req 36.6)
                assertThat(thinkingContent.thinking()).isEqualTo(thinkingSpec.expectedThinking());

                // Verify contentSignature (Req 36.15)
                assertThat(thinkingContent.thinkingSignature()).isEqualTo(thinkingSpec.contentSignature());

            } else if (blockSpec instanceof ToolCallBlockSpecWrapper wrapper) {
                ToolCallBlockSpec toolSpec = wrapper.spec();
                assertThat(content).isInstanceOf(ToolCall.class);
                ToolCall toolCall = (ToolCall) content;

                // Verify tool call id and name (Req 36.7)
                assertThat(toolCall.id()).isEqualTo(toolSpec.id());
                assertThat(toolCall.name()).isEqualTo(toolSpec.toolName());

                // Verify arguments JSON parsing (Req 36.8, 36.9)
                Map<String, Object> expectedArgs = toolSpec.arguments();
                Map<String, Object> actualArgs = toolCall.arguments();

                for (Map.Entry<String, Object> entry : expectedArgs.entrySet()) {
                    assertThat(actualArgs).containsKey(entry.getKey());
                    Object expected = entry.getValue();
                    Object actual = actualArgs.get(entry.getKey());

                    // Handle type coercion (JSON parsing may return Integer vs Long, etc.)
                    if (expected instanceof Number && actual instanceof Number) {
                        assertThat(((Number) actual).doubleValue())
                                .isEqualTo(((Number) expected).doubleValue());
                    } else {
                        assertThat(actual).isEqualTo(expected);
                    }
                }
            }
        }

        // Verify terminal state
        if (spec.endsWithError()) {
            assertThat(partial.getStopReason()).isEqualTo(StopReason.ERROR);
            assertThat(partial.getErrorMessage()).isEqualTo(spec.errorMessage());
        } else {
            assertThat(partial.getStopReason()).isEqualTo(spec.stopReason());
        }

        // Verify usage is set
        assertThat(partial.getUsage()).isEqualTo(spec.usage());
    }

    /**
     * Property: Text delta concatenation produces correct final text.
     *
     * <p>Focused test for text content reconstruction.
     *
     * <p><b>Validates: Requirements 36.5, 36.14</b>
     */
    @Property(tries = 100)
    void textDeltaConcatenationIsCorrect(
            @ForAll("textBlockSpecs") TextBlockSpec textSpec
    ) {
        // Build minimal event sequence with just text content
        List<ProxyAssistantMessageEvent> events = new ArrayList<>();
        events.add(new ProxyAssistantMessageEvent.Start());
        events.add(new ProxyAssistantMessageEvent.TextStart(0));
        for (String delta : textSpec.deltas()) {
            events.add(new ProxyAssistantMessageEvent.TextDelta(0, delta));
        }
        events.add(new ProxyAssistantMessageEvent.TextEnd(0, textSpec.contentSignature()));
        events.add(new ProxyAssistantMessageEvent.Done(StopReason.STOP,
                new Usage(10, 20, 0, 0, 30, null)));

        // Process events
        AssistantMessage partial = createEmptyPartial();
        Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

        for (ProxyAssistantMessageEvent event : events) {
            ProxyStream.processProxyEvent(event, partial, partialJsonMap);
        }

        // Verify
        assertThat(partial.getContent()).hasSize(1);
        TextContent textContent = (TextContent) partial.getContent().get(0);
        assertThat(textContent.text()).isEqualTo(textSpec.expectedText());
        assertThat(textContent.textSignature()).isEqualTo(textSpec.contentSignature());
    }

    /**
     * Property: Thinking delta concatenation produces correct final thinking.
     *
     * <p>Focused test for thinking content reconstruction.
     *
     * <p><b>Validates: Requirements 36.6, 36.15</b>
     */
    @Property(tries = 100)
    void thinkingDeltaConcatenationIsCorrect(
            @ForAll("thinkingBlockSpecs") ThinkingBlockSpec thinkingSpec
    ) {
        // Build minimal event sequence with just thinking content
        List<ProxyAssistantMessageEvent> events = new ArrayList<>();
        events.add(new ProxyAssistantMessageEvent.Start());
        events.add(new ProxyAssistantMessageEvent.ThinkingStart(0));
        for (String delta : thinkingSpec.deltas()) {
            events.add(new ProxyAssistantMessageEvent.ThinkingDelta(0, delta));
        }
        events.add(new ProxyAssistantMessageEvent.ThinkingEnd(0, thinkingSpec.contentSignature()));
        events.add(new ProxyAssistantMessageEvent.Done(StopReason.STOP,
                new Usage(10, 20, 0, 0, 30, null)));

        // Process events
        AssistantMessage partial = createEmptyPartial();
        Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

        for (ProxyAssistantMessageEvent event : events) {
            ProxyStream.processProxyEvent(event, partial, partialJsonMap);
        }

        // Verify
        assertThat(partial.getContent()).hasSize(1);
        ThinkingContent thinkingContent = (ThinkingContent) partial.getContent().get(0);
        assertThat(thinkingContent.thinking()).isEqualTo(thinkingSpec.expectedThinking());
        assertThat(thinkingContent.thinkingSignature()).isEqualTo(thinkingSpec.contentSignature());
    }

    /**
     * Property: Tool call delta JSON parsing produces correct arguments.
     *
     * <p>Focused test for tool call reconstruction.
     *
     * <p><b>Validates: Requirements 36.7, 36.8, 36.9</b>
     */
    @Property(tries = 100)
    void toolCallDeltaJsonParsingIsCorrect(
            @ForAll("toolCallBlockSpecs") ToolCallBlockSpec toolSpec
    ) {
        // Build minimal event sequence with just tool call content
        List<ProxyAssistantMessageEvent> events = new ArrayList<>();
        events.add(new ProxyAssistantMessageEvent.Start());
        events.add(new ProxyAssistantMessageEvent.ToolCallStart(0, toolSpec.id(), toolSpec.toolName()));
        for (String delta : toolSpec.toJsonDeltas()) {
            events.add(new ProxyAssistantMessageEvent.ToolCallDelta(0, delta));
        }
        events.add(new ProxyAssistantMessageEvent.ToolCallEnd(0));
        events.add(new ProxyAssistantMessageEvent.Done(StopReason.TOOL_USE,
                new Usage(10, 20, 0, 0, 30, null)));

        // Process events
        AssistantMessage partial = createEmptyPartial();
        Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

        for (ProxyAssistantMessageEvent event : events) {
            ProxyStream.processProxyEvent(event, partial, partialJsonMap);
        }

        // Verify
        assertThat(partial.getContent()).hasSize(1);
        ToolCall toolCall = (ToolCall) partial.getContent().get(0);
        assertThat(toolCall.id()).isEqualTo(toolSpec.id());
        assertThat(toolCall.name()).isEqualTo(toolSpec.toolName());

        // Verify arguments
        Map<String, Object> expectedArgs = toolSpec.arguments();
        Map<String, Object> actualArgs = toolCall.arguments();

        for (Map.Entry<String, Object> entry : expectedArgs.entrySet()) {
            assertThat(actualArgs).containsKey(entry.getKey());
            Object expected = entry.getValue();
            Object actual = actualArgs.get(entry.getKey());

            if (expected instanceof Number && actual instanceof Number) {
                assertThat(((Number) actual).doubleValue())
                        .isEqualTo(((Number) expected).doubleValue());
            } else {
                assertThat(actual).isEqualTo(expected);
            }
        }
    }

    /**
     * Property: Multiple content blocks are reconstructed in correct order.
     *
     * <p>Tests that content blocks maintain their index positions.
     */
    @Property(tries = 50)
    void multipleContentBlocksReconstructedInOrder(
            @ForAll("textBlockSpecs") TextBlockSpec text1,
            @ForAll("thinkingBlockSpecs") ThinkingBlockSpec thinking,
            @ForAll("textBlockSpecs") TextBlockSpec text2
    ) {
        // Build event sequence with multiple content blocks
        List<ProxyAssistantMessageEvent> events = new ArrayList<>();
        events.add(new ProxyAssistantMessageEvent.Start());

        // Text block at index 0
        events.add(new ProxyAssistantMessageEvent.TextStart(0));
        for (String delta : text1.deltas()) {
            events.add(new ProxyAssistantMessageEvent.TextDelta(0, delta));
        }
        events.add(new ProxyAssistantMessageEvent.TextEnd(0, text1.contentSignature()));

        // Thinking block at index 1
        events.add(new ProxyAssistantMessageEvent.ThinkingStart(1));
        for (String delta : thinking.deltas()) {
            events.add(new ProxyAssistantMessageEvent.ThinkingDelta(1, delta));
        }
        events.add(new ProxyAssistantMessageEvent.ThinkingEnd(1, thinking.contentSignature()));

        // Text block at index 2
        events.add(new ProxyAssistantMessageEvent.TextStart(2));
        for (String delta : text2.deltas()) {
            events.add(new ProxyAssistantMessageEvent.TextDelta(2, delta));
        }
        events.add(new ProxyAssistantMessageEvent.TextEnd(2, text2.contentSignature()));

        events.add(new ProxyAssistantMessageEvent.Done(StopReason.STOP,
                new Usage(10, 20, 0, 0, 30, null)));

        // Process events
        AssistantMessage partial = createEmptyPartial();
        Map<Integer, StringBuilder> partialJsonMap = new HashMap<>();

        for (ProxyAssistantMessageEvent event : events) {
            ProxyStream.processProxyEvent(event, partial, partialJsonMap);
        }

        // Verify order and content
        assertThat(partial.getContent()).hasSize(3);

        TextContent tc1 = (TextContent) partial.getContent().get(0);
        assertThat(tc1.text()).isEqualTo(text1.expectedText());

        ThinkingContent thc = (ThinkingContent) partial.getContent().get(1);
        assertThat(thc.thinking()).isEqualTo(thinking.expectedThinking());

        TextContent tc2 = (TextContent) partial.getContent().get(2);
        assertThat(tc2.text()).isEqualTo(text2.expectedText());
    }

    // ==================== Helper methods ====================

    private AssistantMessage createEmptyPartial() {
        return AssistantMessage.builder()
                .content(new ArrayList<>())
                .api("test-api")
                .provider("test-provider")
                .model("test-model")
                .usage(new Usage(0, 0, 0, 0, 0, new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}

package com.pi.ai.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.pi.ai.core.types.*;

import net.jqwik.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * 工具函数属性测试集。
 *
 * <p>验证 Properties 10, 15-24。
 *
 * <p><b>Validates: Requirements 20.1-20.5, 22.1-22.5, 23.1-23.5,
 * 25.1-25.6, 26.1-26.5, 27.1-27.3, 28.1-28.2</b>
 */
class UtilPropertyTest {

    // ==================== Property 10: ToolValidator ====================

    /**
     * 属性：有效参数通过校验。
     */
    @Property(tries = 100)
    void toolValidator_validArgs_passes(
            @ForAll("validToolNames") String toolName
    ) {
        Tool tool = new Tool(toolName, "test tool",
                PiAiJson.MAPPER.createObjectNode()
                        .put("type", "object")
                        .set("properties", PiAiJson.MAPPER.createObjectNode()
                                .set("name", PiAiJson.MAPPER.createObjectNode().put("type", "string"))));

        ToolCall toolCall = new ToolCall("id-1", toolName, Map.of("name", "test"));

        Map<String, Object> result = ToolValidator.validateToolCall(List.of(tool), toolCall);
        assertThat(result).containsEntry("name", "test");
    }

    /**
     * 属性：工具名不在列表中时抛出异常。
     */
    @Property(tries = 100)
    void toolValidator_unknownTool_throws(
            @ForAll("validToolNames") String toolName,
            @ForAll("validToolNames") String otherName
    ) {
        Assume.that(!toolName.equals(otherName));

        Tool tool = new Tool(toolName, "test tool", PiAiJson.MAPPER.createObjectNode().put("type", "object"));
        ToolCall toolCall = new ToolCall("id-1", otherName, Map.of());

        assertThatThrownBy(() -> ToolValidator.validateToolCall(List.of(tool), toolCall))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(otherName);
    }

    @Provide
    Arbitrary<String> validToolNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
    }

    // ==================== Property 15: parseStreamingJson round-trip ====================

    /**
     * 属性：完整 JSON 解析结果与标准解析一致。
     */
    @Property(tries = 200)
    void parseStreamingJson_completeJson_matchesStandard(
            @ForAll("simpleJsonKeys") String key,
            @ForAll("simpleJsonValues") String value
    ) {
        String json = "{\"" + escapeJson(key) + "\":\"" + escapeJson(value) + "\"}";
        Map<String, Object> result = StreamingJsonParser.parseStreamingJson(json);
        assertThat(result).containsEntry(key, value);
    }

    // ==================== Property 16: parseStreamingJson 容错性 ====================

    /**
     * 属性：截断的 JSON 不抛出异常。
     */
    @Property(tries = 300)
    void parseStreamingJson_truncatedJson_neverThrows(
            @ForAll("simpleJsonKeys") String key,
            @ForAll("simpleJsonValues") String value,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 50) int cutPoint
    ) {
        String json = "{\"" + escapeJson(key) + "\":\"" + escapeJson(value) + "\"}";
        int actualCut = Math.min(cutPoint, json.length());
        String truncated = json.substring(0, actualCut);

        // 不应抛出异常
        Map<String, Object> result = StreamingJsonParser.parseStreamingJson(truncated);
        assertThat(result).isNotNull();
    }

    /**
     * 属性：null 和空字符串返回空 Map。
     */
    @Example
    void parseStreamingJson_nullOrEmpty_returnsEmptyMap() {
        assertThat(StreamingJsonParser.parseStreamingJson(null)).isEmpty();
        assertThat(StreamingJsonParser.parseStreamingJson("")).isEmpty();
        assertThat(StreamingJsonParser.parseStreamingJson("   ")).isEmpty();
    }

    @Provide
    Arbitrary<String> simpleJsonKeys() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10);
    }

    @Provide
    Arbitrary<String> simpleJsonValues() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(0).ofMaxLength(20);
    }

    // ==================== Property 17: isContextOverflow ====================

    /**
     * 属性：已知溢出错误模式被检测到。
     */
    @Property(tries = 50)
    void isContextOverflow_knownPatterns_detected(@ForAll("overflowMessages") String errorMsg) {
        AssistantMessage msg = AssistantMessage.builder()
                .stopReason(StopReason.ERROR)
                .errorMessage(errorMsg)
                .build();

        assertThat(ContextOverflow.isContextOverflow(msg, 0)).isTrue();
    }

    /**
     * 属性：stopReason 为 stop 且 usage 超过 contextWindow 时检测到静默溢出。
     */
    @Property(tries = 100)
    void isContextOverflow_silentOverflow_detected(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1000, max = 100000) int contextWindow,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 50000) int excess
    ) {
        int inputTokens = contextWindow + excess;
        Usage usage = new Usage(inputTokens, 100, 0, 0, inputTokens + 100, null);
        AssistantMessage msg = AssistantMessage.builder()
                .stopReason(StopReason.STOP)
                .usage(usage)
                .build();

        assertThat(ContextOverflow.isContextOverflow(msg, contextWindow)).isTrue();
    }

    /**
     * 属性：不匹配任何模式时返回 false。
     */
    @Property(tries = 100)
    void isContextOverflow_noMatch_returnsFalse(@ForAll("nonOverflowMessages") String errorMsg) {
        AssistantMessage msg = AssistantMessage.builder()
                .stopReason(StopReason.ERROR)
                .errorMessage(errorMsg)
                .build();

        assertThat(ContextOverflow.isContextOverflow(msg, 0)).isFalse();
    }

    @Provide
    Arbitrary<String> overflowMessages() {
        return Arbitraries.of(
                "prompt is too long: 213462 tokens > 200000 maximum",
                "Your input exceeds the context window of this model",
                "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)",
                "This model's maximum prompt length is 131072 but the request contains 537812 tokens",
                "Please reduce the length of the messages or completion",
                "This endpoint's maximum context length is 128000 tokens",
                "prompt token count of 200000 exceeds the limit of 128000",
                "the request exceeds the available context size",
                "tokens to keep from the initial prompt is greater than the context length",
                "context window exceeds limit",
                "Your request exceeded model token limit: 128000",
                "too large for model with 128000 maximum context length",
                "model_context_window_exceeded",
                "context_length_exceeded",
                "too many tokens in the request",
                "token limit exceeded",
                "400 (no body)",
                "413 status code (no body)"
        );
    }

    @Provide
    Arbitrary<String> nonOverflowMessages() {
        return Arbitraries.of(
                "rate limit exceeded",
                "authentication failed",
                "internal server error",
                "bad request",
                "model not found",
                "invalid api key"
        );
    }

    // ==================== Property 18: sanitizeSurrogates 恒等性 ====================

    /**
     * 属性：不含代理字符的普通文本，sanitizeSurrogates 返回相同字符串。
     */
    @Property(tries = 500)
    void sanitizeSurrogates_normalText_identity(@ForAll("normalTexts") String text) {
        assertThat(UnicodeSanitizer.sanitizeSurrogates(text)).isEqualTo(text);
    }

    @Provide
    Arbitrary<String> normalTexts() {
        // 只生成 BMP 范围内非代理区的字符
        return Arbitraries.strings()
                .withCharRange('\u0020', '\uD7FF')
                .ofMinLength(0)
                .ofMaxLength(100);
    }

    // ==================== Property 19: sanitizeSurrogates 代理对处理 ====================

    /**
     * 属性：输出中不存在未配对的代理字符。
     */
    @Property(tries = 500)
    void sanitizeSurrogates_output_noUnpairedSurrogates(@ForAll("textsWithSurrogates") String text) {
        String result = UnicodeSanitizer.sanitizeSurrogates(text);

        for (int i = 0; i < result.length(); i++) {
            char ch = result.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                assertThat(i + 1).isLessThan(result.length());
                assertThat(Character.isLowSurrogate(result.charAt(i + 1))).isTrue();
                i++; // 跳过低代理
            } else {
                assertThat(Character.isSurrogate(ch)).isFalse();
            }
        }
    }

    @Provide
    Arbitrary<String> textsWithSurrogates() {
        // 混合普通字符、未配对高代理、未配对低代理、正确配对的代理对
        Arbitrary<Character> normalChars = Arbitraries.chars().range('\u0020', '\u007E');
        Arbitrary<Character> highSurrogates = Arbitraries.chars().range('\uD800', '\uDBFF');
        Arbitrary<Character> lowSurrogates = Arbitraries.chars().range('\uDC00', '\uDFFF');

        return Arbitraries.frequencyOf(
                Tuple.of(5, normalChars.map(String::valueOf)),
                Tuple.of(1, highSurrogates.map(String::valueOf)),
                Tuple.of(1, lowSurrogates.map(String::valueOf)),
                Tuple.of(2, Combinators.combine(highSurrogates, lowSurrogates)
                        .as((h, l) -> "" + h + l))
        ).list().ofMinSize(0).ofMaxSize(50)
                .map(parts -> String.join("", parts));
    }

    // ==================== Property 20: shortHash 确定性 ====================

    /**
     * 属性：多次调用返回相同结果。
     */
    @Property(tries = 500)
    void shortHash_deterministic(@ForAll String input) {
        String hash1 = ShortHash.shortHash(input);
        String hash2 = ShortHash.shortHash(input);
        assertThat(hash1).isEqualTo(hash2);
    }

    /**
     * 属性：结果为有效的 Base36 编码字符串。
     */
    @Property(tries = 500)
    void shortHash_validBase36(@ForAll String input) {
        String hash = ShortHash.shortHash(input);
        assertThat(hash).isNotEmpty();
        assertThat(hash).matches("[0-9a-z]+");
    }

    // ==================== Property 21: buildBaseOptions 默认 maxTokens ====================

    /**
     * 属性：maxTokens 未设置时，默认为 min(model.maxTokens, 32000)。
     */
    @Property(tries = 200)
    void buildBaseOptions_defaultMaxTokens(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 200000) int modelMaxTokens
    ) {
        Model model = testModel(modelMaxTokens);
        StreamOptions result = SimpleOptions.buildBaseOptions(model, null, null);

        assertThat(result.getMaxTokens()).isEqualTo(Math.min(modelMaxTokens, 32000));
    }

    /**
     * 属性：maxTokens 已设置时，使用设置的值。
     */
    @Property(tries = 200)
    void buildBaseOptions_customMaxTokens(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 200000) int modelMaxTokens,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 100000) int customMaxTokens
    ) {
        Model model = testModel(modelMaxTokens);
        SimpleStreamOptions options = SimpleStreamOptions.simpleBuilder()
                .maxTokens(customMaxTokens)
                .build();
        StreamOptions result = SimpleOptions.buildBaseOptions(model, options, null);

        assertThat(result.getMaxTokens()).isEqualTo(customMaxTokens);
    }

    // ==================== Property 22: adjustMaxTokensForThinking ====================

    /**
     * 属性：返回的 maxTokens ≤ modelMaxTokens。
     */
    @Property(tries = 300)
    void adjustMaxTokensForThinking_maxTokensCapped(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 100000) int baseMaxTokens,
            @ForAll @net.jqwik.api.constraints.IntRange(min = 1, max = 200000) int modelMaxTokens,
            @ForAll("thinkingLevels") ThinkingLevel level
    ) {
        SimpleOptions.ThinkingResult result = SimpleOptions.adjustMaxTokensForThinking(
                baseMaxTokens, modelMaxTokens, level, null);

        assertThat(result.maxTokens()).isLessThanOrEqualTo(modelMaxTokens);
    }

    /**
     * 属性：使用默认预算时，预算值正确。
     */
    @Example
    void adjustMaxTokensForThinking_defaultBudgets() {
        // minimal=1024
        var r1 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.MINIMAL, null);
        assertThat(r1.maxTokens()).isEqualTo(4096 + 1024);
        assertThat(r1.thinkingBudget()).isEqualTo(1024);

        // low=2048
        var r2 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.LOW, null);
        assertThat(r2.maxTokens()).isEqualTo(4096 + 2048);
        assertThat(r2.thinkingBudget()).isEqualTo(2048);

        // medium=8192
        var r3 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.MEDIUM, null);
        assertThat(r3.maxTokens()).isEqualTo(4096 + 8192);
        assertThat(r3.thinkingBudget()).isEqualTo(8192);

        // high=16384
        var r4 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.HIGH, null);
        assertThat(r4.maxTokens()).isEqualTo(4096 + 16384);
        assertThat(r4.thinkingBudget()).isEqualTo(16384);
    }

    /**
     * 属性：自定义预算覆盖默认值。
     */
    @Example
    void adjustMaxTokensForThinking_customBudgets() {
        ThinkingBudgets custom = new ThinkingBudgets(512, null, null, 32768);
        var r1 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.MINIMAL, custom);
        assertThat(r1.thinkingBudget()).isEqualTo(512);

        var r2 = SimpleOptions.adjustMaxTokensForThinking(4096, 200000, ThinkingLevel.HIGH, custom);
        assertThat(r2.thinkingBudget()).isEqualTo(32768);
    }

    @Provide
    Arbitrary<ThinkingLevel> thinkingLevels() {
        return Arbitraries.of(ThinkingLevel.MINIMAL, ThinkingLevel.LOW,
                ThinkingLevel.MEDIUM, ThinkingLevel.HIGH);
    }

    // ==================== Property 23: clampReasoning 降级 ====================

    /**
     * 属性：XHIGH 降级为 HIGH，其他不变。
     */
    @Example
    void clampReasoning_xhighToHigh() {
        assertThat(SimpleOptions.clampReasoning(ThinkingLevel.XHIGH)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(SimpleOptions.clampReasoning(ThinkingLevel.HIGH)).isEqualTo(ThinkingLevel.HIGH);
        assertThat(SimpleOptions.clampReasoning(ThinkingLevel.MEDIUM)).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(SimpleOptions.clampReasoning(ThinkingLevel.LOW)).isEqualTo(ThinkingLevel.LOW);
        assertThat(SimpleOptions.clampReasoning(ThinkingLevel.MINIMAL)).isEqualTo(ThinkingLevel.MINIMAL);
        assertThat(SimpleOptions.clampReasoning(null)).isNull();
    }

    // ==================== Property 24: StringEnum JSON Schema 格式 ====================

    /**
     * 属性：生成的 JSON Schema 包含 type="string" 和正确的 enum 数组。
     */
    @Property(tries = 100)
    void stringEnum_correctFormat(@ForAll("stringLists") List<String> values) {
        JsonNode schema = StringEnumHelper.stringEnum(values);

        assertThat(schema.get("type").asText()).isEqualTo("string");
        assertThat(schema.has("enum")).isTrue();

        JsonNode enumNode = schema.get("enum");
        assertThat(enumNode.isArray()).isTrue();
        assertThat(enumNode.size()).isEqualTo(values.size());

        for (int i = 0; i < values.size(); i++) {
            assertThat(enumNode.get(i).asText()).isEqualTo(values.get(i));
        }
    }

    /**
     * 属性：带描述时包含 description 字段。
     */
    @Property(tries = 50)
    void stringEnum_withDescription(@ForAll("stringLists") List<String> values, @ForAll String desc) {
        Assume.that(!desc.isEmpty());
        JsonNode schema = StringEnumHelper.stringEnum(values, desc);

        assertThat(schema.get("description").asText()).isEqualTo(desc);
    }

    @Provide
    Arbitrary<List<String>> stringLists() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10)
                .list().ofMinSize(1).ofMaxSize(10);
    }

    // ==================== 辅助方法 ====================

    private static Model testModel(int maxTokens) {
        return new Model(
                "test-model", "Test Model", "test-api", "test-provider",
                "https://api.test.com", false, List.of("text"),
                new ModelCost(1.0, 2.0, 0.5, 0.5), 128000, maxTokens,
                null, null
        );
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

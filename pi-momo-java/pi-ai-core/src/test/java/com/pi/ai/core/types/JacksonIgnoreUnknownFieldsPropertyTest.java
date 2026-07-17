package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：Jackson 反序列化忽略未知字段。
 *
 * <p>对每个核心类型：先序列化为 JSON，再注入随机未知字段，
 * 验证反序列化后的结果与不注入未知字段时反序列化的结果等价。
 * 这证明了 FAIL_ON_UNKNOWN_PROPERTIES=false 配置正确生效。
 *
 * <p><b>Validates: Requirements 35.4</b>
 */
class JacksonIgnoreUnknownFieldsPropertyTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== Arbitrary 生成器 ====================

    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(100)
                .alpha().numeric();
    }

    @Provide
    Arbitrary<String> base64Data() {
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(50)
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=");
    }

    @Provide
    Arbitrary<String> mimeTypes() {
        return Arbitraries.of("image/png", "image/jpeg", "image/gif", "image/webp");
    }

    /** 生成随机未知字段名（"_unknown_" 前缀避免与已知字段冲突）。 */
    @Provide
    Arbitrary<String> unknownFieldNames() {
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(20).alpha()
                .map(s -> "_unknown_" + s);
    }

    /** 生成随机非 null JSON 值。 */
    @Provide
    Arbitrary<Object> unknownFieldValues() {
        return Arbitraries.oneOf(
                safeStrings().map(s -> (Object) s),
                Arbitraries.integers().between(-1000, 1000).map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b)
        );
    }

    /** 生成 1~3 个随机未知字段键值对。 */
    @Provide
    Arbitrary<List<Map.Entry<String, Object>>> unknownFields() {
        Arbitrary<Map.Entry<String, Object>> entryArb =
                Combinators.combine(unknownFieldNames(), unknownFieldValues())
                        .as((k, v) -> Map.entry(k, v));
        return entryArb.list().ofMinSize(1).ofMaxSize(3);
    }

    // --- ContentBlock 子类型生成器 ---

    @Provide
    Arbitrary<TextContent> textContents() {
        return Combinators.combine(safeStrings(), safeStrings().injectNull(0.5))
                .as((text, sig) -> new TextContent("text", text, sig));
    }

    @Provide
    Arbitrary<ThinkingContent> thinkingContents() {
        return Combinators.combine(
                safeStrings(), safeStrings().injectNull(0.5),
                Arbitraries.of(true, false).map(b -> (Boolean) b).injectNull(0.5)
        ).as((thinking, sig, redacted) -> new ThinkingContent("thinking", thinking, sig, redacted));
    }

    @Provide
    Arbitrary<ImageContent> imageContents() {
        return Combinators.combine(base64Data(), mimeTypes())
                .as((data, mime) -> new ImageContent("image", data, mime));
    }

    @Provide
    Arbitrary<ToolCall> toolCalls() {
        Arbitrary<Map<String, Object>> argsArb = Combinators.combine(safeStrings(), safeStrings())
                .as((k, v) -> Map.<String, Object>of(k, v));
        return Combinators.combine(safeStrings(), safeStrings(), argsArb, safeStrings().injectNull(0.5))
                .as((id, name, args, sig) -> new ToolCall("toolCall", id, name, args, sig));
    }

    @Provide
    Arbitrary<AssistantContentBlock> assistantContentBlocks() {
        return Arbitraries.oneOf(
                textContents().map(t -> t), thinkingContents().map(t -> t), toolCalls().map(t -> t));
    }

    // --- Usage 生成器 ---

    @Provide
    Arbitrary<Usage.Cost> usageCosts() {
        Arbitrary<Double> d = Arbitraries.doubles().between(0.0, 100.0);
        return Combinators.combine(d, d, d, d, d).as(Usage.Cost::new);
    }

    @Provide
    Arbitrary<Usage> usages() {
        Arbitrary<Integer> i = Arbitraries.integers().between(0, 100000);
        return Combinators.combine(i, i, i, i, i, usageCosts().injectNull(0.3)).as(Usage::new);
    }

    @Provide
    Arbitrary<StopReason> stopReasons() { return Arbitraries.of(StopReason.values()); }

    @Provide
    Arbitrary<Long> timestamps() { return Arbitraries.longs().between(0L, 2000000000000L); }

    // --- Message 子类型生成器 ---

    @Provide
    Arbitrary<UserMessage> userMessages() {
        return Combinators.combine(safeStrings(), timestamps())
                .as((text, ts) -> new UserMessage("user", text, ts));
    }

    @Provide
    Arbitrary<AssistantMessage> assistantMessages() {
        return Combinators.combine(
                assistantContentBlocks().list().ofMinSize(0).ofMaxSize(3),
                safeStrings(), safeStrings(), safeStrings(),
                usages(), stopReasons(), safeStrings().injectNull(0.5), timestamps()
        ).as((content, api, prov, model, usage, sr, err, ts) ->
                AssistantMessage.builder().content(content).api(api).provider(prov).model(model)
                        .usage(usage).stopReason(sr).errorMessage(err).timestamp(ts).build());
    }

    @Provide
    Arbitrary<ToolResultMessage> toolResultMessages() {
        return Combinators.combine(
                safeStrings(), safeStrings(),
                textContents().map(t -> (UserContentBlock) t).list().ofMinSize(0).ofMaxSize(3),
                Arbitraries.of(true, false), timestamps()
        ).as((cid, tn, content, err, ts) ->
                new ToolResultMessage("toolResult", cid, tn, content, null, err, ts));
    }

    // ==================== 辅助方法 ====================

    /**
     * 在 JSON 树中注入随机未知字段。
     */
    private ObjectNode injectFields(ObjectNode node, List<Map.Entry<String, Object>> fields) {
        ObjectNode copy = node.deepCopy();
        for (Map.Entry<String, Object> entry : fields) {
            copy.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
        }
        return copy;
    }

    /**
     * 核心验证逻辑：对给定类型，序列化→注入未知字段→反序列化，
     * 与序列化→反序列化（无注入）的结果进行比较。
     *
     * <p>这种方式避免了 @JsonTypeInfo 消费 type/role 字段导致的 null 问题，
     * 因为两边都经过相同的反序列化路径。
     */
    private <T> void assertIgnoresUnknownFields(
            T original, Class<T> type, List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        // 序列化为 JSON 树
        ObjectNode jsonTree = (ObjectNode) MAPPER.valueToTree(original);

        // 基线：正常反序列化（无未知字段）
        T baseline = MAPPER.treeToValue(jsonTree, type);

        // 注入未知字段后反序列化
        ObjectNode modified = injectFields(jsonTree, fields);
        T withUnknown = MAPPER.treeToValue(modified, type);

        // 两者应等价
        assertThat(withUnknown).isEqualTo(baseline);
    }

    /**
     * 多态版本：通过基类型反序列化。
     */
    private <T> void assertPolymorphicIgnoresUnknownFields(
            T original, Class<? super T> baseType, Class<T> expectedType,
            List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        ObjectNode jsonTree = (ObjectNode) MAPPER.valueToTree(original);

        Object baseline = MAPPER.treeToValue(jsonTree, baseType);
        assertThat(baseline).isInstanceOf(expectedType);

        ObjectNode modified = injectFields(jsonTree, fields);
        Object withUnknown = MAPPER.treeToValue(modified, baseType);
        assertThat(withUnknown).isInstanceOf(expectedType);

        assertThat(withUnknown).isEqualTo(baseline);
    }

    // ==================== 属性测试 ====================

    // --- ContentBlock 子类型 ---

    /** TextContent：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void textContent_ignoresUnknownFields(
            @ForAll("textContents") TextContent original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, TextContent.class, fields);
    }

    /** ThinkingContent：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void thinkingContent_ignoresUnknownFields(
            @ForAll("thinkingContents") ThinkingContent original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, ThinkingContent.class, fields);
    }

    /** ImageContent：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void imageContent_ignoresUnknownFields(
            @ForAll("imageContents") ImageContent original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, ImageContent.class, fields);
    }

    /** ToolCall：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void toolCall_ignoresUnknownFields(
            @ForAll("toolCalls") ToolCall original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, ToolCall.class, fields);
    }

    // --- Usage ---

    /** Usage：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void usage_ignoresUnknownFields(
            @ForAll("usages") Usage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, Usage.class, fields);
    }

    /** Usage.Cost：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void usageCost_ignoresUnknownFields(
            @ForAll("usageCosts") Usage.Cost original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, Usage.Cost.class, fields);
    }

    // --- Message 子类型 ---

    /** UserMessage：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void userMessage_ignoresUnknownFields(
            @ForAll("userMessages") UserMessage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, UserMessage.class, fields);
    }

    /** AssistantMessage：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void assistantMessage_ignoresUnknownFields(
            @ForAll("assistantMessages") AssistantMessage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, AssistantMessage.class, fields);
    }

    /** ToolResultMessage：注入未知字段后反序列化结果等价。 */
    @Property(tries = 200)
    void toolResultMessage_ignoresUnknownFields(
            @ForAll("toolResultMessages") ToolResultMessage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertIgnoresUnknownFields(original, ToolResultMessage.class, fields);
    }

    // --- 多态反序列化 ---

    /** TextContent 通过 ContentBlock 多态反序列化：注入未知字段后仍成功。 */
    @Property(tries = 100)
    void textContent_polymorphicIgnoresUnknownFields(
            @ForAll("textContents") TextContent original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertPolymorphicIgnoresUnknownFields(original, ContentBlock.class, TextContent.class, fields);
    }

    /** AssistantMessage 通过 Message 多态反序列化：注入未知字段后仍成功。 */
    @Property(tries = 100)
    void assistantMessage_polymorphicIgnoresUnknownFields(
            @ForAll("assistantMessages") AssistantMessage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertPolymorphicIgnoresUnknownFields(original, Message.class, AssistantMessage.class, fields);
    }

    /** ToolResultMessage 通过 Message 多态反序列化：注入未知字段后仍成功。 */
    @Property(tries = 100)
    void toolResultMessage_polymorphicIgnoresUnknownFields(
            @ForAll("toolResultMessages") ToolResultMessage original,
            @ForAll("unknownFields") List<Map.Entry<String, Object>> fields
    ) throws JsonProcessingException {
        assertPolymorphicIgnoresUnknownFields(original, Message.class, ToolResultMessage.class, fields);
    }
}

package com.pi.ai.core.types;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：Jackson 序列化 round-trip。
 *
 * <p>验证所有核心类型（Message 子类型、ContentBlock 子类型、Usage）
 * 经过 Jackson 序列化→反序列化后产生与原始对象等价的实例。
 *
 * <p><b>Validates: Requirements 1.1-1.9, 2.1-2.3, 35.1-35.3</b>
 */
class JacksonRoundTripPropertyTest {

    private static final ObjectMapper MAPPER = PiAiJson.MAPPER;

    // ==================== Arbitrary 生成器 ====================

    /**
     * 生成非空可打印字符串，避免 JSON 序列化问题。
     */
    @Provide
    Arbitrary<String> safeStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(100)
                .alpha()
                .numeric();
    }

    /**
     * 生成 Base64 风格的数据字符串。
     */
    @Provide
    Arbitrary<String> base64Data() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .withChars("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=");
    }

    /**
     * 生成 MIME 类型字符串。
     */
    @Provide
    Arbitrary<String> mimeTypes() {
        return Arbitraries.of("image/png", "image/jpeg", "image/gif", "image/webp");
    }

    // --- ContentBlock 子类型生成器 ---

    @Provide
    Arbitrary<TextContent> textContents() {
        return Combinators.combine(
                safeStrings(),
                safeStrings().injectNull(0.5)
        ).as((text, sig) -> new TextContent("text", text, sig));
    }

    @Provide
    Arbitrary<ThinkingContent> thinkingContents() {
        return Combinators.combine(
                safeStrings(),
                safeStrings().injectNull(0.5),
                Arbitraries.of(true, false).map(b -> (Boolean) b).injectNull(0.5)
        ).as((thinking, sig, redacted) ->
                new ThinkingContent("thinking", thinking, sig, redacted));
    }

    @Provide
    Arbitrary<ImageContent> imageContents() {
        return Combinators.combine(
                base64Data(),
                mimeTypes()
        ).as((data, mime) -> new ImageContent("image", data, mime));
    }

    @Provide
    Arbitrary<ToolCall> toolCalls() {
        // 生成简单的 String 值 arguments，确保 round-trip 等价
        Arbitrary<Map<String, Object>> argsArb = Combinators.combine(
                safeStrings(),
                safeStrings()
        ).as((k, v) -> Map.<String, Object>of(k, v));

        return Combinators.combine(
                safeStrings(),
                safeStrings(),
                argsArb,
                safeStrings().injectNull(0.5)
        ).as((id, name, args, thoughtSig) ->
                new ToolCall("toolCall", id, name, args, thoughtSig));
    }

    // --- AssistantContentBlock 多态生成器 ---

    @Provide
    Arbitrary<AssistantContentBlock> assistantContentBlocks() {
        return Arbitraries.oneOf(
                textContents().map(t -> t),
                thinkingContents().map(t -> t),
                toolCalls().map(t -> t)
        );
    }

    // --- UserContentBlock 多态生成器 ---

    @Provide
    Arbitrary<UserContentBlock> userContentBlocks() {
        return Arbitraries.oneOf(
                textContents().map(t -> t),
                imageContents().map(t -> t)
        );
    }

    // --- Usage 生成器 ---

    @Provide
    Arbitrary<Usage.Cost> usageCosts() {
        Arbitrary<Double> posDouble = Arbitraries.doubles().between(0.0, 100.0);
        return Combinators.combine(posDouble, posDouble, posDouble, posDouble, posDouble)
                .as(Usage.Cost::new);
    }

    @Provide
    Arbitrary<Usage> usages() {
        Arbitrary<Integer> posInt = Arbitraries.integers().between(0, 100000);
        return Combinators.combine(posInt, posInt, posInt, posInt, posInt, usageCosts().injectNull(0.3))
                .as(Usage::new);
    }

    // --- StopReason 生成器（排除 null） ---

    @Provide
    Arbitrary<StopReason> stopReasons() {
        return Arbitraries.of(StopReason.values());
    }

    // --- Message 子类型生成器 ---

    @Provide
    Arbitrary<Long> timestamps() {
        return Arbitraries.longs().between(0L, 2000000000000L);
    }

    /**
     * 生成 UserMessage（使用纯文本 content 确保 round-trip 等价）。
     * 注意：UserMessage.content 类型为 Object，List 内容反序列化后不保持类型等价。
     */
    @Provide
    Arbitrary<UserMessage> userMessages() {
        return Combinators.combine(safeStrings(), timestamps())
                .as((text, ts) -> new UserMessage("user", text, ts));
    }

    /**
     * 生成 AssistantMessage。
     */
    @Provide
    Arbitrary<AssistantMessage> assistantMessages() {
        return Combinators.combine(
                assistantContentBlocks().list().ofMinSize(0).ofMaxSize(3),
                safeStrings(),
                safeStrings(),
                safeStrings(),
                usages(),
                stopReasons(),
                safeStrings().injectNull(0.5),
                timestamps()
        ).as((content, api, provider, model, usage, stopReason, errorMsg, ts) ->
                AssistantMessage.builder()
                        .content(content)
                        .api(api)
                        .provider(provider)
                        .model(model)
                        .usage(usage)
                        .stopReason(stopReason)
                        .errorMessage(errorMsg)
                        .timestamp(ts)
                        .build());
    }

    /**
     * 生成 ToolResultMessage（details 设为 null 确保 round-trip 等价）。
     */
    @Provide
    Arbitrary<ToolResultMessage> toolResultMessages() {
        return Combinators.combine(
                safeStrings(),
                safeStrings(),
                textContents().map(t -> (UserContentBlock) t).list().ofMinSize(0).ofMaxSize(3),
                Arbitraries.of(true, false),
                timestamps()
        ).as((callId, toolName, content, isError, ts) ->
                new ToolResultMessage("toolResult", callId, toolName, content, null, isError, ts));
    }

    // ==================== 属性测试 ====================

    // --- ContentBlock 子类型 round-trip ---

    /**
     * TextContent 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void textContent_roundTrip(@ForAll("textContents") TextContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        TextContent deserialized = MAPPER.readValue(json, TextContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * TextContent 通过 ContentBlock 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void textContent_polymorphicRoundTrip(@ForAll("textContents") TextContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ContentBlock deserialized = MAPPER.readValue(json, ContentBlock.class);
        assertThat(deserialized).isInstanceOf(TextContent.class).isEqualTo(original);
    }

    /**
     * ThinkingContent 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void thinkingContent_roundTrip(@ForAll("thinkingContents") ThinkingContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ThinkingContent deserialized = MAPPER.readValue(json, ThinkingContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * ThinkingContent 通过 ContentBlock 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void thinkingContent_polymorphicRoundTrip(@ForAll("thinkingContents") ThinkingContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ContentBlock deserialized = MAPPER.readValue(json, ContentBlock.class);
        assertThat(deserialized).isInstanceOf(ThinkingContent.class).isEqualTo(original);
    }

    /**
     * ImageContent 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void imageContent_roundTrip(@ForAll("imageContents") ImageContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ImageContent deserialized = MAPPER.readValue(json, ImageContent.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * ImageContent 通过 UserContentBlock 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void imageContent_polymorphicRoundTrip(@ForAll("imageContents") ImageContent original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        UserContentBlock deserialized = MAPPER.readValue(json, UserContentBlock.class);
        assertThat(deserialized).isInstanceOf(ImageContent.class).isEqualTo(original);
    }

    /**
     * ToolCall 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void toolCall_roundTrip(@ForAll("toolCalls") ToolCall original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ToolCall deserialized = MAPPER.readValue(json, ToolCall.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * ToolCall 通过 AssistantContentBlock 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void toolCall_polymorphicRoundTrip(@ForAll("toolCalls") ToolCall original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        AssistantContentBlock deserialized = MAPPER.readValue(json, AssistantContentBlock.class);
        assertThat(deserialized).isInstanceOf(ToolCall.class).isEqualTo(original);
    }

    // --- Usage round-trip ---

    /**
     * Usage 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void usage_roundTrip(@ForAll("usages") Usage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        Usage deserialized = MAPPER.readValue(json, Usage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * Usage.Cost 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void usageCost_roundTrip(@ForAll("usageCosts") Usage.Cost original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        Usage.Cost deserialized = MAPPER.readValue(json, Usage.Cost.class);
        assertThat(deserialized).isEqualTo(original);
    }

    // --- Message 子类型 round-trip ---

    /**
     * UserMessage 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void userMessage_roundTrip(@ForAll("userMessages") UserMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        UserMessage deserialized = MAPPER.readValue(json, UserMessage.class);
        assertThat(deserialized.role()).isEqualTo(original.role());
        assertThat(deserialized.content()).isEqualTo(original.content());
        assertThat(deserialized.timestamp()).isEqualTo(original.timestamp());
    }

    /**
     * UserMessage 通过 Message 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void userMessage_polymorphicRoundTrip(@ForAll("userMessages") UserMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertThat(deserialized).isInstanceOf(UserMessage.class);
        UserMessage um = (UserMessage) deserialized;
        assertThat(um.role()).isEqualTo(original.role());
        assertThat(um.content()).isEqualTo(original.content());
        assertThat(um.timestamp()).isEqualTo(original.timestamp());
    }

    /**
     * AssistantMessage 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void assistantMessage_roundTrip(@ForAll("assistantMessages") AssistantMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        AssistantMessage deserialized = MAPPER.readValue(json, AssistantMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * AssistantMessage 通过 Message 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void assistantMessage_polymorphicRoundTrip(@ForAll("assistantMessages") AssistantMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertThat(deserialized).isInstanceOf(AssistantMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * ToolResultMessage 序列化→反序列化 round-trip。
     */
    @Property(tries = 200)
    void toolResultMessage_roundTrip(@ForAll("toolResultMessages") ToolResultMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        ToolResultMessage deserialized = MAPPER.readValue(json, ToolResultMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }

    /**
     * ToolResultMessage 通过 Message 多态反序列化 round-trip。
     */
    @Property(tries = 100)
    void toolResultMessage_polymorphicRoundTrip(@ForAll("toolResultMessages") ToolResultMessage original) throws JsonProcessingException {
        String json = MAPPER.writeValueAsString(original);
        Message deserialized = MAPPER.readValue(json, Message.class);
        assertThat(deserialized).isInstanceOf(ToolResultMessage.class);
        assertThat(deserialized).isEqualTo(original);
    }
}

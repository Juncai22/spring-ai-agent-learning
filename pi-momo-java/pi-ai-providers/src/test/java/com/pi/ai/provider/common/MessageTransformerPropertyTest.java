package com.pi.ai.provider.common;

import com.pi.ai.core.types.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨 Provider 消息转换属性测试。
 *
 * <p>Property 11: thinking 块处理
 * <p>Property 12: ToolCall ID 同步
 * <p>Property 13: 孤立 ToolCall 补全
 * <p>Property 14: 错误消息过滤
 */
class MessageTransformerPropertyTest {

    // 目标模型（用于判断 isSameModel）
    private static final Model TARGET_MODEL = new Model(
            "claude-sonnet-4-20250514", "Claude Sonnet 4",
            "anthropic-messages", "anthropic",
            "https://api.anthropic.com", true,
            List.of("text", "image"),
            new ModelCost(3.0, 15.0, 0.3, 3.75),
            200000, 8192, null, null);

    // 不同模型（跨 Provider）
    private static final Model DIFFERENT_MODEL = new Model(
            "gpt-4o", "GPT-4o",
            "openai-completions", "openai",
            "https://api.openai.com", false,
            List.of("text", "image"),
            new ModelCost(5.0, 15.0, 0, 0),
            128000, 4096, null, null);

    // ========== Property 11: thinking 块处理 ==========

    @Property(tries = 50)
    void thinkingFromDifferentModel_convertedToText() {
        // 来自不同模型的 thinking 块应转为 TextContent
        ThinkingContent thinking = new ThinkingContent("thinking", "I need to think about this", "sig123", null);
        AssistantMessage am = buildAssistantMessage("openai-completions", "openai", "gpt-4o",
                List.of(thinking));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        assertThat(transformed.getContent()).hasSize(1);
        assertThat(transformed.getContent().get(0)).isInstanceOf(TextContent.class);
        TextContent tc = (TextContent) transformed.getContent().get(0);
        assertThat(tc.text()).isEqualTo("I need to think about this");
    }

    @Property(tries = 50)
    void redactedThinkingFromDifferentModel_dropped() {
        // 来自不同模型的 redacted thinking 应被丢弃
        ThinkingContent redacted = new ThinkingContent("thinking", "[Reasoning redacted]", "encrypted_data", true);
        AssistantMessage am = buildAssistantMessage("openai-completions", "openai", "gpt-4o",
                List.of(redacted, new TextContent("hello")));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        // redacted thinking 被丢弃，只剩 TextContent
        assertThat(transformed.getContent()).hasSize(1);
        assertThat(transformed.getContent().get(0)).isInstanceOf(TextContent.class);
    }

    @Property(tries = 50)
    void redactedThinkingFromSameModel_preserved() {
        // 来自同模型的 redacted thinking 应保留
        ThinkingContent redacted = new ThinkingContent("thinking", "[Reasoning redacted]", "encrypted_data", true);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(redacted));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        assertThat(transformed.getContent()).hasSize(1);
        assertThat(transformed.getContent().get(0)).isInstanceOf(ThinkingContent.class);
        ThinkingContent tc = (ThinkingContent) transformed.getContent().get(0);
        assertThat(tc.redacted()).isTrue();
    }

    @Property(tries = 50)
    void thinkingWithSignatureFromSameModel_preserved() {
        // 同模型且有 thinkingSignature 的 thinking 应保留
        ThinkingContent thinking = new ThinkingContent("thinking", "", "sig_abc", null);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(thinking));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        assertThat(transformed.getContent()).hasSize(1);
        assertThat(transformed.getContent().get(0)).isInstanceOf(ThinkingContent.class);
    }

    @Property(tries = 50)
    void emptyThinking_dropped() {
        // 空 thinking 块应被丢弃（跨模型时）
        ThinkingContent empty = new ThinkingContent("thinking", "  ", null, null);
        AssistantMessage am = buildAssistantMessage("openai-completions", "openai", "gpt-4o",
                List.of(empty, new TextContent("hello")));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        assertThat(transformed.getContent()).hasSize(1);
        assertThat(transformed.getContent().get(0)).isInstanceOf(TextContent.class);
    }

    @Property(tries = 50)
    void thoughtSignatureFromDifferentModel_removed() {
        // 跨模型的 ToolCall thoughtSignature 应被移除
        ToolCall tc = new ToolCall("toolCall", "call_123", "search",
                Map.of("q", "test"), "thought_sig");
        AssistantMessage am = buildAssistantMessage("openai-completions", "openai", "gpt-4o",
                List.of(tc));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        AssistantMessage transformed = (AssistantMessage) result.get(0);
        ToolCall transformedTc = (ToolCall) transformed.getContent().get(0);
        assertThat(transformedTc.thoughtSignature()).isNull();
    }

    // ========== Property 12: ToolCall ID 同步 ==========

    @Property(tries = 50)
    void toolCallIdNormalization_syncsWithToolResult() {
        // ToolCall ID 规范化后，对应的 ToolResultMessage 也应同步更新
        ToolCall tc = new ToolCall("toolCall", "very_long_id_from_openai_responses_api",
                "search", Map.of("q", "test"), null);
        AssistantMessage am = buildAssistantMessage("openai-completions", "openai", "gpt-4o",
                List.of(tc));
        ToolResultMessage trm = new ToolResultMessage("toolResult",
                "very_long_id_from_openai_responses_api", "search",
                List.of(new TextContent("result")), null, false, System.currentTimeMillis());

        MessageTransformer.NormalizeToolCallId normalizer =
                (id, model, source) -> "normalized_" + id.substring(0, 5);

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am, trm), TARGET_MODEL, normalizer);

        assertThat(result).hasSize(2);
        AssistantMessage transformedAm = (AssistantMessage) result.get(0);
        ToolCall transformedTc = (ToolCall) transformedAm.getContent().get(0);
        assertThat(transformedTc.id()).isEqualTo("normalized_very_");

        ToolResultMessage transformedTrm = (ToolResultMessage) result.get(1);
        assertThat(transformedTrm.toolCallId()).isEqualTo("normalized_very_");
    }

    @Property(tries = 50)
    void sameModelToolCallId_notNormalized() {
        // 同模型的 ToolCall ID 不应被规范化
        ToolCall tc = new ToolCall("toolCall", "original_id", "search",
                Map.of("q", "test"), null);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(tc));

        MessageTransformer.NormalizeToolCallId normalizer =
                (id, model, source) -> "should_not_be_called";

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am), TARGET_MODEL, normalizer);

        AssistantMessage transformed = (AssistantMessage) result.get(0);
        ToolCall transformedTc = (ToolCall) transformed.getContent().get(0);
        assertThat(transformedTc.id()).isEqualTo("original_id");
    }

    // ========== Property 13: 孤立 ToolCall 补全 ==========

    @Property(tries = 50)
    void orphanedToolCall_syntheticToolResultInserted() {
        // 没有对应 ToolResultMessage 的 ToolCall 应插入合成 ToolResult
        ToolCall tc = new ToolCall("toolCall", "orphan_call", "search",
                Map.of("q", "test"), null);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(tc));
        // 紧接着一个新的 AssistantMessage（没有 ToolResult）
        AssistantMessage am2 = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(new TextContent("next response")));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am, am2), TARGET_MODEL, null);

        // 应该是：am, synthetic ToolResult, am2
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage synthetic = (ToolResultMessage) result.get(1);
        assertThat(synthetic.toolCallId()).isEqualTo("orphan_call");
        assertThat(synthetic.isError()).isTrue();
        assertThat(result.get(2)).isInstanceOf(AssistantMessage.class);
    }

    @Property(tries = 50)
    void toolCallWithResult_noSyntheticInserted() {
        // 有对应 ToolResultMessage 的 ToolCall 不应插入合成 ToolResult
        ToolCall tc = new ToolCall("toolCall", "matched_call", "search",
                Map.of("q", "test"), null);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(tc));
        ToolResultMessage trm = new ToolResultMessage("toolResult", "matched_call", "search",
                List.of(new TextContent("result")), null, false, System.currentTimeMillis());

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am, trm), TARGET_MODEL, null);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1)).isInstanceOf(ToolResultMessage.class);
    }

    @Property(tries = 50)
    void orphanedToolCallBeforeUserMessage_syntheticInserted() {
        // 用户消息中断工具流时，也应插入合成 ToolResult
        ToolCall tc = new ToolCall("toolCall", "orphan_before_user", "search",
                Map.of("q", "test"), null);
        AssistantMessage am = buildAssistantMessage("anthropic-messages", "anthropic", "claude-sonnet-4-20250514",
                List.of(tc));
        UserMessage um = new UserMessage("user", "hello", System.currentTimeMillis());

        List<Message> result = MessageTransformer.transformMessages(
                List.of(am, um), TARGET_MODEL, null);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isInstanceOf(AssistantMessage.class);
        assertThat(result.get(1)).isInstanceOf(ToolResultMessage.class);
        ToolResultMessage synthetic = (ToolResultMessage) result.get(1);
        assertThat(synthetic.toolCallId()).isEqualTo("orphan_before_user");
        assertThat(synthetic.isError()).isTrue();
        assertThat(result.get(2)).isInstanceOf(UserMessage.class);
    }

    // ========== Property 14: 错误消息过滤 ==========

    @Property(tries = 50)
    void errorAssistantMessage_filtered() {
        // stopReason 为 error 的 AssistantMessage 应被过滤
        AssistantMessage errorMsg = AssistantMessage.builder()
                .content(List.of(new TextContent("partial")))
                .api("anthropic-messages").provider("anthropic").model("claude-sonnet-4-20250514")
                .stopReason(StopReason.ERROR)
                .errorMessage("Something went wrong")
                .timestamp(System.currentTimeMillis())
                .build();

        List<Message> result = MessageTransformer.transformMessages(
                List.of(errorMsg), TARGET_MODEL, null);

        assertThat(result).isEmpty();
    }

    @Property(tries = 50)
    void abortedAssistantMessage_filtered() {
        // stopReason 为 aborted 的 AssistantMessage 应被过滤
        AssistantMessage abortedMsg = AssistantMessage.builder()
                .content(List.of(new TextContent("partial")))
                .api("anthropic-messages").provider("anthropic").model("claude-sonnet-4-20250514")
                .stopReason(StopReason.ABORTED)
                .timestamp(System.currentTimeMillis())
                .build();

        List<Message> result = MessageTransformer.transformMessages(
                List.of(abortedMsg), TARGET_MODEL, null);

        assertThat(result).isEmpty();
    }

    @Property(tries = 50)
    void normalAssistantMessage_notFiltered() {
        // stopReason 为 stop 的 AssistantMessage 不应被过滤
        AssistantMessage normalMsg = buildAssistantMessage("anthropic-messages", "anthropic",
                "claude-sonnet-4-20250514", List.of(new TextContent("hello")));

        List<Message> result = MessageTransformer.transformMessages(
                List.of(normalMsg), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
    }

    @Property(tries = 50)
    void userMessages_passThrough() {
        // 用户消息应直接透传
        UserMessage um = new UserMessage("user", "hello world", System.currentTimeMillis());

        List<Message> result = MessageTransformer.transformMessages(
                List.of(um), TARGET_MODEL, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) result.get(0)).content()).isEqualTo("hello world");
    }

    // ========== 辅助方法 ==========

    private AssistantMessage buildAssistantMessage(String api, String provider, String model,
                                                    List<AssistantContentBlock> content) {
        return AssistantMessage.builder()
                .content(new ArrayList<>(content))
                .api(api)
                .provider(provider)
                .model(model)
                .usage(new Usage(100, 50, 0, 0, 150, new Usage.Cost(0, 0, 0, 0, 0)))
                .stopReason(StopReason.STOP)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}

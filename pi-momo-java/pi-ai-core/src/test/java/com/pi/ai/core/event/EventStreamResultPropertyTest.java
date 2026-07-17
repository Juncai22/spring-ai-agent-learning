package com.pi.ai.core.event;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：EventStream 终止事件完成 result Future。
 *
 * <p>验证当 push 满足 isComplete 条件的事件时，result() 返回的 CompletableFuture
 * 正确完成，且值等于 extractResult 应用于该事件的结果。
 *
 * <p>同时验证 AssistantMessageEventStream 中 Done/Error 事件正确完成 result Future，
 * 非终止事件不会完成 result Future，以及第一个终止事件优先（后续被忽略）。
 *
 * <p><b>Validates: Requirements 5.3, 5.5, 5.9</b>
 */
class EventStreamResultPropertyTest {

    // ==================== Arbitrary 生成器 ====================

    /**
     * 生成随机非终止字符串事件（不等于 "DONE"）。
     */
    @Provide
    Arbitrary<String> nonTerminalStrings() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .alpha()
                .numeric()
                .filter(s -> !"DONE".equals(s));
    }

    /**
     * 生成随机非终止字符串事件列表。
     */
    @Provide
    Arbitrary<List<String>> nonTerminalStringLists() {
        return nonTerminalStrings().list().ofMinSize(0).ofMaxSize(50);
    }

    /**
     * 生成随机 AssistantMessage 用于测试。
     */
    @Provide
    Arbitrary<AssistantMessage> assistantMessages() {
        return Combinators.combine(
                Arbitraries.strings().ofMinLength(1).ofMaxLength(30).alpha(),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha(),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha(),
                Arbitraries.longs().between(0, Long.MAX_VALUE / 2)
        ).as((text, model, provider, ts) ->
                AssistantMessage.builder()
                        .content(List.of(new TextContent(text)))
                        .model(model)
                        .provider(provider)
                        .timestamp(ts)
                        .build()
        );
    }

    /**
     * 生成随机 StopReason 用于 Done 事件。
     */
    @Provide
    Arbitrary<StopReason> doneReasons() {
        return Arbitraries.of(StopReason.STOP, StopReason.LENGTH, StopReason.TOOL_USE);
    }

    /**
     * 生成随机 StopReason 用于 Error 事件。
     */
    @Provide
    Arbitrary<StopReason> errorReasons() {
        return Arbitraries.of(StopReason.ERROR, StopReason.ABORTED);
    }

    /**
     * 生成随机非终止 AssistantMessageEvent 列表（不含 Done/Error）。
     */
    @Provide
    Arbitrary<List<AssistantMessageEvent>> nonTerminalEvents() {
        Arbitrary<AssistantMessageEvent> eventArb = Combinators.combine(
                Arbitraries.integers().between(0, 5),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha(),
                assistantMessages()
        ).as((idx, delta, partial) -> switch (idx) {
            case 0 -> new AssistantMessageEvent.Start(partial);
            case 1 -> new AssistantMessageEvent.TextStart(0, partial);
            case 2 -> new AssistantMessageEvent.TextDelta(0, delta, partial);
            case 3 -> new AssistantMessageEvent.ThinkingStart(0, partial);
            case 4 -> new AssistantMessageEvent.ThinkingDelta(0, delta, partial);
            default -> new AssistantMessageEvent.ToolCallStart(0, partial);
        });
        return eventArb.list().ofMinSize(0).ofMaxSize(20);
    }

    // ==================== 泛型 EventStream 属性测试 ====================

    /**
     * 属性：push 终止事件后，result Future 完成且值为 extractResult(event) 的结果。
     *
     * <p>对任意非终止事件前缀 + 一个终止事件 "DONE"，push 后 result() 应完成，
     * 且值等于 extractResult 应用于 "DONE" 的结果。
     *
     * <p><b>Validates: Requirements 5.3, 5.5</b>
     */
    @Property(tries = 500)
    void genericStream_terminalEvent_completesResultFuture(
            @ForAll("nonTerminalStringLists") List<String> prefix) {

        // extractResult 将终止事件转为大写（验证 extractResult 被正确调用）
        @SuppressWarnings("resource")
        var stream = new EventStream<String, String>(
                "DONE"::equals,
                String::toUpperCase
        );

        // 推送非终止事件
        for (String event : prefix) {
            stream.push(event);
        }

        // 推送前 result 不应完成
        assertThat(stream.result().isDone()).isFalse();

        // 推送终止事件
        stream.push("DONE");

        // result 应立即完成，值为 extractResult("DONE") = "DONE"（大写不变）
        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isEqualTo("DONE");

        stream.end(null);
    }

    /**
     * 属性：非终止事件不会完成 result Future。
     *
     * <p>对任意非终止事件列表，push 后 result() 不应完成。
     *
     * <p><b>Validates: Requirements 5.3, 5.5</b>
     */
    @Property(tries = 500)
    void genericStream_nonTerminalEvents_doNotCompleteResult(
            @ForAll("nonTerminalStringLists") List<String> events) {

        @SuppressWarnings("resource")
        var stream = new EventStream<String, String>(
                "DONE"::equals,
                event -> event
        );

        for (String event : events) {
            stream.push(event);
        }

        // 没有终止事件，result 不应完成
        assertThat(stream.result().isDone()).isFalse();

        stream.end(null);
    }

    /**
     * 属性：第一个终止事件的 extractResult 值优先，后续终止事件被忽略。
     *
     * <p>push 第一个终止事件后 done=true，后续 push 是空操作，
     * result 值应为第一个终止事件的 extractResult 结果。
     *
     * <p><b>Validates: Requirements 5.3, 5.5</b>
     */
    @Property(tries = 200)
    void genericStream_firstTerminalEventWins(
            @ForAll("nonTerminalStringLists") List<String> prefix) {

        // 使用自定义 extractResult：返回事件本身
        @SuppressWarnings("resource")
        var stream = new EventStream<String, String>(
                s -> s.startsWith("TERM_"),
                event -> event
        );

        for (String event : prefix) {
            stream.push(event);
        }

        // 推送第一个终止事件
        stream.push("TERM_first");
        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isEqualTo("TERM_first");

        // 推送第二个终止事件（应被忽略，因为 done=true）
        stream.push("TERM_second");

        // result 值仍为第一个终止事件
        assertThat(stream.result().join()).isEqualTo("TERM_first");

        stream.end(null);
    }

    // ==================== AssistantMessageEventStream 属性测试 ====================

    /**
     * 属性：Done 事件 push 后，result Future 完成且值为 done.message()。
     *
     * <p>对任意非终止事件前缀和随机 Done 事件，push Done 后 result() 应完成，
     * 且值为 Done 事件携带的 AssistantMessage。
     *
     * <p><b>Validates: Requirements 5.9</b>
     */
    @Property(tries = 300)
    void assistantStream_doneEvent_completesResultWithMessage(
            @ForAll("nonTerminalEvents") List<AssistantMessageEvent> prefix,
            @ForAll("doneReasons") StopReason reason,
            @ForAll("assistantMessages") AssistantMessage finalMsg) {

        @SuppressWarnings("resource")
        var stream = new AssistantMessageEventStream();

        // 推送非终止事件
        for (AssistantMessageEvent event : prefix) {
            stream.push(event);
        }

        // 推送前 result 不应完成
        assertThat(stream.result().isDone()).isFalse();

        // 推送 Done 事件
        var doneEvent = new AssistantMessageEvent.Done(reason, finalMsg);
        stream.push(doneEvent);

        // result 应立即完成，值为 Done 事件携带的 message
        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(finalMsg);

        stream.end(null);
    }

    /**
     * 属性：Error 事件 push 后，result Future 完成且值为 error.error()。
     *
     * <p>对任意非终止事件前缀和随机 Error 事件，push Error 后 result() 应完成，
     * 且值为 Error 事件携带的 AssistantMessage。
     *
     * <p><b>Validates: Requirements 5.9</b>
     */
    @Property(tries = 300)
    void assistantStream_errorEvent_completesResultWithErrorMessage(
            @ForAll("nonTerminalEvents") List<AssistantMessageEvent> prefix,
            @ForAll("errorReasons") StopReason reason,
            @ForAll("assistantMessages") AssistantMessage errorMsg) {

        @SuppressWarnings("resource")
        var stream = new AssistantMessageEventStream();

        for (AssistantMessageEvent event : prefix) {
            stream.push(event);
        }

        assertThat(stream.result().isDone()).isFalse();

        // 推送 Error 事件
        var errorEvent = new AssistantMessageEvent.Error(reason, errorMsg);
        stream.push(errorEvent);

        // result 应立即完成，值为 Error 事件携带的 error message
        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(errorMsg);

        stream.end(null);
    }

    /**
     * 属性：非终止 AssistantMessageEvent 不会完成 result Future。
     *
     * <p>对任意非终止事件列表（不含 Done/Error），push 后 result() 不应完成。
     *
     * <p><b>Validates: Requirements 5.3, 5.9</b>
     */
    @Property(tries = 300)
    void assistantStream_nonTerminalEvents_doNotCompleteResult(
            @ForAll("nonTerminalEvents") List<AssistantMessageEvent> events) {

        @SuppressWarnings("resource")
        var stream = new AssistantMessageEventStream();

        for (AssistantMessageEvent event : events) {
            stream.push(event);
        }

        // 没有 Done 或 Error 事件，result 不应完成
        assertThat(stream.result().isDone()).isFalse();

        stream.end(null);
    }

    /**
     * 属性：第一个终止事件（Done 或 Error）优先，后续终止事件被忽略。
     *
     * <p>push 第一个 Done 事件后 done=true，后续 push 的 Error 事件被忽略，
     * result 值应为第一个 Done 事件的 message。
     *
     * <p><b>Validates: Requirements 5.3, 5.5, 5.9</b>
     */
    @Property(tries = 200)
    void assistantStream_firstTerminalEventWins(
            @ForAll("assistantMessages") AssistantMessage firstMsg,
            @ForAll("assistantMessages") AssistantMessage secondMsg) {

        @SuppressWarnings("resource")
        var stream = new AssistantMessageEventStream();

        // 推送第一个终止事件（Done）
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, firstMsg));
        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(firstMsg);

        // 推送第二个终止事件（Error）— 应被忽略（done=true）
        stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, secondMsg));

        // result 值仍为第一个终止事件的 message
        assertThat(stream.result().join()).isSameAs(firstMsg);

        stream.end(null);
    }
}

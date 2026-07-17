package com.pi.ai.core.event;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.TextContent;
import com.pi.ai.core.types.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：AssistantMessageEvent 事件类型和 AssistantMessageEventStream 特化类。
 * 覆盖 12 种事件类型的创建和 type() 值、Done/Error 事件完成 result Future、
 * 事件顺序保持、非终止事件不完成 result Future。
 */
class AssistantMessageEventStreamTest {

    // ==================== 12 种事件类型的 type() 值 ====================

    @Test
    void startEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.Start(buildPartialMessage());
        assertThat(event.type()).isEqualTo("start");
        assertThat(event.partial()).isNotNull();
    }

    @Test
    void textStartEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.TextStart(0, buildPartialMessage());
        assertThat(event.type()).isEqualTo("text_start");
        assertThat(event.contentIndex()).isEqualTo(0);
    }

    @Test
    void textDeltaEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.TextDelta(0, "hello", buildPartialMessage());
        assertThat(event.type()).isEqualTo("text_delta");
        assertThat(event.delta()).isEqualTo("hello");
    }

    @Test
    void textEndEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.TextEnd(0, "full text", buildPartialMessage());
        assertThat(event.type()).isEqualTo("text_end");
        assertThat(event.content()).isEqualTo("full text");
    }

    @Test
    void thinkingStartEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.ThinkingStart(1, buildPartialMessage());
        assertThat(event.type()).isEqualTo("thinking_start");
        assertThat(event.contentIndex()).isEqualTo(1);
    }

    @Test
    void thinkingDeltaEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.ThinkingDelta(1, "思考中...", buildPartialMessage());
        assertThat(event.type()).isEqualTo("thinking_delta");
        assertThat(event.delta()).isEqualTo("思考中...");
    }

    @Test
    void thinkingEndEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.ThinkingEnd(1, "完整思考", buildPartialMessage());
        assertThat(event.type()).isEqualTo("thinking_end");
        assertThat(event.content()).isEqualTo("完整思考");
    }

    @Test
    void toolCallStartEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.ToolCallStart(2, buildPartialMessage());
        assertThat(event.type()).isEqualTo("toolcall_start");
        assertThat(event.contentIndex()).isEqualTo(2);
    }

    @Test
    void toolCallDeltaEvent_hasCorrectType() {
        var event = new AssistantMessageEvent.ToolCallDelta(2, "{\"arg\":", buildPartialMessage());
        assertThat(event.type()).isEqualTo("toolcall_delta");
        assertThat(event.delta()).isEqualTo("{\"arg\":");
    }

    @Test
    void toolCallEndEvent_hasCorrectType() {
        var toolCall = new ToolCall("tc-1", "search", Map.of("query", "test"));
        var event = new AssistantMessageEvent.ToolCallEnd(2, toolCall, buildPartialMessage());
        assertThat(event.type()).isEqualTo("toolcall_end");
        assertThat(event.toolCall().name()).isEqualTo("search");
    }

    @Test
    void doneEvent_hasCorrectType() {
        var msg = buildFinalMessage(StopReason.STOP);
        var event = new AssistantMessageEvent.Done(StopReason.STOP, msg);
        assertThat(event.type()).isEqualTo("done");
        assertThat(event.reason()).isEqualTo(StopReason.STOP);
        assertThat(event.message()).isSameAs(msg);
    }

    @Test
    void errorEvent_hasCorrectType() {
        var msg = buildErrorMessage();
        var event = new AssistantMessageEvent.Error(StopReason.ERROR, msg);
        assertThat(event.type()).isEqualTo("error");
        assertThat(event.reason()).isEqualTo(StopReason.ERROR);
        assertThat(event.error()).isSameAs(msg);
    }

    // ==================== Done 事件完成 result Future ====================

    @Test
    void doneEvent_completesResultFutureWithMessage() {
        var stream = new AssistantMessageEventStream();
        var finalMsg = buildFinalMessage(StopReason.STOP);

        stream.push(new AssistantMessageEvent.Start(buildPartialMessage()));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(finalMsg);
    }

    @Test
    void doneEvent_withToolUseReason_completesResult() {
        var stream = new AssistantMessageEventStream();
        var finalMsg = buildFinalMessage(StopReason.TOOL_USE);

        stream.push(new AssistantMessageEvent.Done(StopReason.TOOL_USE, finalMsg));
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(finalMsg);
    }

    // ==================== Error 事件完成 result Future ====================

    @Test
    void errorEvent_completesResultFutureWithErrorMessage() {
        var stream = new AssistantMessageEventStream();
        var errorMsg = buildErrorMessage();

        stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, errorMsg));
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(errorMsg);
    }

    @Test
    void errorEvent_withAbortedReason_completesResult() {
        var stream = new AssistantMessageEventStream();
        var errorMsg = buildErrorMessage();

        stream.push(new AssistantMessageEvent.Error(StopReason.ABORTED, errorMsg));
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(errorMsg);
    }

    // ==================== 事件顺序保持 ====================

    @Test
    void eventsAreDeliveredInOrder() {
        var stream = new AssistantMessageEventStream();
        var partial = buildPartialMessage();
        var finalMsg = buildFinalMessage(StopReason.STOP);

        stream.push(new AssistantMessageEvent.Start(partial));
        stream.push(new AssistantMessageEvent.TextStart(0, partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, "Hello", partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, " world", partial));
        stream.push(new AssistantMessageEvent.TextEnd(0, "Hello world", partial));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));
        stream.end(null);

        List<AssistantMessageEvent> events = collectAll(stream);
        assertThat(events).hasSize(6);
        assertThat(events.get(0).type()).isEqualTo("start");
        assertThat(events.get(1).type()).isEqualTo("text_start");
        assertThat(events.get(2).type()).isEqualTo("text_delta");
        assertThat(events.get(3).type()).isEqualTo("text_delta");
        assertThat(events.get(4).type()).isEqualTo("text_end");
        assertThat(events.get(5).type()).isEqualTo("done");
    }

    // ==================== 非终止事件不完成 result Future ====================

    @Test
    void nonTerminalEvents_doNotCompleteResultFuture() {
        var stream = new AssistantMessageEventStream();
        var partial = buildPartialMessage();

        stream.push(new AssistantMessageEvent.Start(partial));
        stream.push(new AssistantMessageEvent.TextStart(0, partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, "hello", partial));
        stream.push(new AssistantMessageEvent.ThinkingStart(1, partial));
        stream.push(new AssistantMessageEvent.ToolCallStart(2, partial));

        // 没有 Done 或 Error 事件，result 不应完成
        assertThat(stream.result().isDone()).isFalse();

        stream.end(null);
    }

    // ==================== 工厂方法 ====================

    @Test
    void createFactoryMethod_returnsWorkingStream() {
        var stream = AssistantMessageEventStream.create();
        var finalMsg = buildFinalMessage(StopReason.STOP);

        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isSameAs(finalMsg);
    }

    // ==================== 完整流式场景 ====================

    @Test
    void fullStreamingScenario_textGeneration() {
        var stream = new AssistantMessageEventStream();
        var partial = buildPartialMessage();
        var finalMsg = buildFinalMessage(StopReason.STOP);

        // 模拟完整的文本生成流
        stream.push(new AssistantMessageEvent.Start(partial));
        stream.push(new AssistantMessageEvent.TextStart(0, partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, "Hello", partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, ", ", partial));
        stream.push(new AssistantMessageEvent.TextDelta(0, "world!", partial));
        stream.push(new AssistantMessageEvent.TextEnd(0, "Hello, world!", partial));
        stream.push(new AssistantMessageEvent.Done(StopReason.STOP, finalMsg));
        stream.end(null);

        List<AssistantMessageEvent> events = collectAll(stream);
        assertThat(events).hasSize(7);

        // 验证 delta 内容
        var deltas = events.stream()
                .filter(e -> e instanceof AssistantMessageEvent.TextDelta)
                .map(e -> ((AssistantMessageEvent.TextDelta) e).delta())
                .toList();
        assertThat(deltas).containsExactly("Hello", ", ", "world!");

        assertThat(stream.result().join()).isSameAs(finalMsg);
    }

    @Test
    void fullStreamingScenario_toolCallWithThinking() {
        var stream = new AssistantMessageEventStream();
        var partial = buildPartialMessage();
        var toolCall = new ToolCall("tc-1", "search", Map.of("query", "weather"));
        var finalMsg = buildFinalMessage(StopReason.TOOL_USE);

        // 模拟带思考的工具调用流
        stream.push(new AssistantMessageEvent.Start(partial));
        stream.push(new AssistantMessageEvent.ThinkingStart(0, partial));
        stream.push(new AssistantMessageEvent.ThinkingDelta(0, "让我想想...", partial));
        stream.push(new AssistantMessageEvent.ThinkingEnd(0, "让我想想...", partial));
        stream.push(new AssistantMessageEvent.ToolCallStart(1, partial));
        stream.push(new AssistantMessageEvent.ToolCallDelta(1, "{\"query\":", partial));
        stream.push(new AssistantMessageEvent.ToolCallDelta(1, "\"weather\"}", partial));
        stream.push(new AssistantMessageEvent.ToolCallEnd(1, toolCall, partial));
        stream.push(new AssistantMessageEvent.Done(StopReason.TOOL_USE, finalMsg));
        stream.end(null);

        List<AssistantMessageEvent> events = collectAll(stream);
        assertThat(events).hasSize(9);
        assertThat(stream.result().join()).isSameAs(finalMsg);
    }

    // ==================== 辅助方法 ====================

    private AssistantMessage buildPartialMessage() {
        return AssistantMessage.builder()
                .content(List.of(new TextContent("text", "", null)))
                .model("test-model")
                .provider("test-provider")
                .build();
    }

    private AssistantMessage buildFinalMessage(StopReason reason) {
        return AssistantMessage.builder()
                .content(List.of(new TextContent("text", "Hello, world!", null)))
                .model("test-model")
                .provider("test-provider")
                .stopReason(reason)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private AssistantMessage buildErrorMessage() {
        return AssistantMessage.builder()
                .errorMessage("Something went wrong")
                .stopReason(StopReason.ERROR)
                .model("test-model")
                .provider("test-provider")
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private List<AssistantMessageEvent> collectAll(AssistantMessageEventStream stream) {
        List<AssistantMessageEvent> events = new ArrayList<>();
        for (AssistantMessageEvent event : stream) {
            events.add(event);
        }
        return events;
    }
}

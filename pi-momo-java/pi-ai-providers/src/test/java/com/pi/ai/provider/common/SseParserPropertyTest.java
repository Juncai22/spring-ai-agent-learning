package com.pi.ai.provider.common;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.assertj.core.api.Assertions;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 解析器属性测试。
 *
 * <p>Property 27: SSE 解析器 round-trip —
 * 生成合法 SSE 事件序列，格式化为 SSE 文本后解析，验证结果等价。
 */
class SseParserPropertyTest {

    // ========== Property 27: SSE 解析器 round-trip ==========

    @Property(tries = 200)
    void sseRoundTrip_parsedEventsMatchOriginal(
            @ForAll("sseEventLists") List<SseParser.SseEvent> events) {
        // 格式化为 SSE 文本
        String sseText = formatSseEvents(events);

        // 解析
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        List<SseParser.SseEvent> parsed = new ArrayList<>();
        while (iter.hasNext()) {
            parsed.add(iter.next());
        }

        // 验证数量和内容一致
        assertThat(parsed).hasSameSizeAs(events);
        for (int i = 0; i < events.size(); i++) {
            SseParser.SseEvent expected = events.get(i);
            SseParser.SseEvent actual = parsed.get(i);
            assertThat(actual.data()).isEqualTo(expected.data());
            assertThat(actual.event()).isEqualTo(expected.event());
            assertThat(actual.id()).isEqualTo(expected.id());
        }
    }

    @Property(tries = 100)
    void sseRoundTrip_multiLineDataPreserved(
            @ForAll("multiLineDataEvents") SseParser.SseEvent event) {
        String sseText = formatSseEvents(List.of(event));
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        assertThat(iter.hasNext()).isTrue();
        SseParser.SseEvent parsed = iter.next();
        assertThat(parsed.data()).isEqualTo(event.data());
    }

    @Property(tries = 50)
    void sseDoneMarker_detectedCorrectly() {
        String sseText = "data: [DONE]\n\n";
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        assertThat(iter.hasNext()).isTrue();
        SseParser.SseEvent event = iter.next();
        assertThat(event.isDone()).isTrue();
        assertThat(event.data()).isEqualTo("[DONE]");
    }

    @Property(tries = 50)
    void sseCommentLines_ignored(
            @ForAll @StringLength(min = 0, max = 30) String comment,
            @ForAll @StringLength(min = 1, max = 50) String data) {
        // 过滤掉包含换行符的字符串
        String safeComment = comment.replaceAll("[\\r\\n]", "");
        String safeData = data.replaceAll("[\\r\\n]", "");
        if (safeData.isEmpty()) safeData = "test";

        String sseText = ":" + safeComment + "\ndata: " + safeData + "\n\n";
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        assertThat(iter.hasNext()).isTrue();
        SseParser.SseEvent event = iter.next();
        assertThat(event.data()).isEqualTo(safeData);
    }

    @Property(tries = 50)
    void sseEmptyStream_noEvents() {
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(new byte[0]));
        assertThat(iter.hasNext()).isFalse();
    }

    @Property(tries = 100)
    void sseEventWithAllFields_parsedCorrectly(
            @ForAll @StringLength(min = 1, max = 30) String eventType,
            @ForAll @StringLength(min = 1, max = 50) String data,
            @ForAll @StringLength(min = 1, max = 20) String id) {
        String safeEvent = eventType.replaceAll("[\\r\\n]", "");
        String safeData = data.replaceAll("[\\r\\n]", "");
        String safeId = id.replaceAll("[\\r\\n]", "");
        if (safeEvent.isEmpty()) safeEvent = "message";
        if (safeData.isEmpty()) safeData = "test";
        if (safeId.isEmpty()) safeId = "1";

        String sseText = "event: " + safeEvent + "\ndata: " + safeData + "\nid: " + safeId + "\n\n";
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        assertThat(iter.hasNext()).isTrue();
        SseParser.SseEvent event = iter.next();
        assertThat(event.event()).isEqualTo(safeEvent);
        assertThat(event.data()).isEqualTo(safeData);
        assertThat(event.id()).isEqualTo(safeId);
    }

    @Property(tries = 50)
    void sseConsecutiveEmptyLines_noSpuriousEvents() {
        String sseText = "\n\n\n\ndata: hello\n\n\n\n";
        Iterator<SseParser.SseEvent> iter = SseParser.parse(
                new ByteArrayInputStream(sseText.getBytes(StandardCharsets.UTF_8)));

        List<SseParser.SseEvent> events = new ArrayList<>();
        while (iter.hasNext()) {
            events.add(iter.next());
        }
        assertThat(events).hasSize(1);
        assertThat(events.get(0).data()).isEqualTo("hello");
    }

    // ========== Arbitrary 生成器 ==========

    @Provide
    Arbitrary<List<SseParser.SseEvent>> sseEventLists() {
        return sseEventArbitrary().list().ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    Arbitrary<SseParser.SseEvent> multiLineDataEvents() {
        // 生成包含多行 data 的事件
        return Arbitraries.strings()
                .alpha().numeric()
                .ofMinLength(1).ofMaxLength(20)
                .list().ofMinSize(2).ofMaxSize(5)
                .map(lines -> new SseParser.SseEvent(null, String.join("\n", lines), null));
    }

    private Arbitrary<SseParser.SseEvent> sseEventArbitrary() {
        Arbitrary<String> dataArb = Arbitraries.strings()
                .alpha().numeric().withChars(' ', '{', '}', '"', ':', ',')
                .ofMinLength(1).ofMaxLength(80)
                .map(s -> s.replaceAll("[\\r\\n]", ""));

        Arbitrary<String> eventTypeArb = Arbitraries.of("message", "error", "ping", null);
        Arbitrary<String> idArb = Arbitraries.of("1", "2", "abc", null);

        return Combinators.combine(eventTypeArb, dataArb, idArb)
                .as(SseParser.SseEvent::new);
    }

    // ========== 辅助方法 ==========

    /**
     * 将 SSE 事件列表格式化为标准 SSE 文本。
     */
    private String formatSseEvents(List<SseParser.SseEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (SseParser.SseEvent event : events) {
            if (event.event() != null) {
                sb.append("event: ").append(event.event()).append('\n');
            }
            if (event.id() != null) {
                sb.append("id: ").append(event.id()).append('\n');
            }
            // data 可能包含多行，每行需要单独的 data: 前缀
            if (event.data() != null) {
                String[] lines = event.data().split("\n", -1);
                for (String line : lines) {
                    sb.append("data: ").append(line).append('\n');
                }
            }
            sb.append('\n'); // 空行分隔事件
        }
        return sb.toString();
    }
}

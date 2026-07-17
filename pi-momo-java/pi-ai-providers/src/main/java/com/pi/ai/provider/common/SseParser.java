package com.pi.ai.provider.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * SSE（Server-Sent Events）流解析器——将 HTTP 响应流解析为 SSE 事件迭代器。
 *
 * <p>SSE（Server-Sent Events）是一种基于 HTTP 的流式数据传输协议，被大多数 AI 模型
 * API 用于流式返回推理结果。本解析器遵循 W3C SSE 规范，同时兼容各 AI Provider 的
 * 扩展实现。
 *
 * <h3>支持的特性</h3>
 * <ul>
 *   <li>{@code data:} 字段解析和多行拼接（多个 data: 行用换行符连接）</li>
 *   <li>{@code event:} 字段解析，用于区分不同类型的事件</li>
 *   <li>{@code id:} 字段解析，用于事件 ID 追踪</li>
 *   <li>空行分隔的事件边界（W3C 标准）</li>
 *   <li>{@code data: [DONE]} 终止标记（OpenAI 专有协议）</li>
 *   <li>{@code :} 注释行忽略</li>
 *   <li>连接中断和解析错误处理：生成错误事件而非抛出异常</li>
 *   <li>流结束时的未发送事件数据（最后一段无空行终止的数据）</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * Iterator<SseEvent> events = SseParser.parse(inputStream);
 * while (events.hasNext()) {
 *     SseEvent event = events.next();
 *     if (event.isDone()) break;
 *     // 处理 event.data()
 * }
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>本解析器不是线程安全的，应在单个线程中使用。
 *
 * @see <a href="https://html.spec.whatwg.org/multipage/server-sent-events.html">W3C SSE Specification</a>
 */
public final class SseParser {

    private static final Logger log = LoggerFactory.getLogger(SseParser.class);

    /** OpenAI 风格的终止标记 */
    public static final String DONE_MARKER = "[DONE]";

    private SseParser() {
        // 工具类，禁止实例化
    }

    /**
     * SSE 事件记录——表示一个解析完成的 SSE 事件。
     *
     * <p>一个完整的 SSE 事件由 event 类型、data 数据和 id 组成。
     * 事件之间由空行分隔。此 record 是不可变的线程安全对象。
     *
     * @param event 事件类型（event: 字段的值），可为 null。用于区分不同类型的事件，
     *              如 "message_start"、"content_block_delta"、"message_delta" 等
     * @param data  事件数据（data: 字段的值，多行会自动用换行符拼接）。这是事件的主体内容，
     *              通常为 JSON 格式的字符串
     * @param id    事件 ID（id: 字段的值），可为 null。用于事件重连时的断点续传
     */
    public record SseEvent(String event, String data, String id) {

        /** 判断是否为 [DONE] 终止标记 */
        public boolean isDone() {
            return DONE_MARKER.equals(data);
        }

        /** 判断是否为错误事件（由解析器生成） */
        public boolean isError() {
            return "error".equals(event) && data != null && data.startsWith("SSE parse error:");
        }
    }

    /**
     * 从 InputStream 解析 SSE 事件流，返回事件迭代器。
     *
     * <p>创建一个 {@link SseIterator} 实例，逐行读取输入流，解析 SSE 协议格式的事件。
     * 迭代器在 {@code hasNext()} 和 {@code next()} 调用时惰性解析，不会一次性读取所有事件。
     * 当输入流读取完毕或发生错误时，迭代器会自动关闭底层资源。
     *
     * @param inputStream SSE 数据流，通常来自 HTTP 响应的 body
     * @return 事件迭代器，逐个返回解析出的 SSE 事件。使用完后应通过迭代器消费完所有事件
     *         确保资源释放
     */
    public static Iterator<SseEvent> parse(InputStream inputStream) {
        return new SseIterator(inputStream);
    }

    /**
     * SSE 事件迭代器实现——惰性解析 SSE 事件流。
     *
     * <p>内部维护一个 BufferedReader 逐行读取输入流。每次调用 {@link #hasNext()} 时，
     * 尝试读取下一个完整事件并缓存。如果读取过程中发生 IOException，生成一个错误事件
     * 并标记流结束，确保调用方能够感知到异常。
     *
     * <p>SSE 协议解析规则：
     * <ul>
     *   <li>每行格式为 "field: value"，field 可以是 data、event、id 等</li>
     *   <li>冒号后的第一个空格是可选的</li>
     *   <li>空行表示事件边界</li>
     *   <li>以 ":" 开头的行是注释，忽略</li>
     *   <li>多个 data: 字段拼接成一个事件（用换行符分隔）</li>
     *   <li>流结束时如果还有未发送的数据，也作为一个事件返回</li>
     * </ul>
     */
    private static class SseIterator implements Iterator<SseEvent> {

        private final BufferedReader reader;
        private SseEvent nextEvent;
        private boolean finished;

        SseIterator(InputStream inputStream) {
            this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.finished = false;
            this.nextEvent = null;
        }

        @Override
        public boolean hasNext() {
            if (finished) return false;
            if (nextEvent != null) return true;

            try {
                nextEvent = readNextEvent();
                if (nextEvent == null) {
                    finished = true;
                    closeReader();
                    return false;
                }
                return true;
            } catch (IOException e) {
                log.debug("SSE 流读取异常", e);
                // 生成错误事件
                nextEvent = new SseEvent("error", "SSE parse error: " + e.getMessage(), null);
                finished = true;
                closeReader();
                return true;
            }
        }

        @Override
        public SseEvent next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            SseEvent event = nextEvent;
            nextEvent = null;
            return event;
        }

        /**
         * 读取下一个完整的 SSE 事件。
         * 空行分隔事件边界，返回 null 表示流结束。
         */
        private SseEvent readNextEvent() throws IOException {
            StringBuilder dataBuilder = null;
            String eventType = null;
            String eventId = null;
            boolean hasFields = false;

            String line;
            while ((line = reader.readLine()) != null) {
                // 空行 = 事件边界
                if (line.isEmpty()) {
                    if (hasFields && dataBuilder != null) {
                        return new SseEvent(eventType, dataBuilder.toString(), eventId);
                    }
                    // 连续空行或无数据字段，继续读取
                    continue;
                }

                // 忽略注释行
                if (line.startsWith(":")) {
                    continue;
                }

                // 解析字段
                int colonIdx = line.indexOf(':');
                String fieldName;
                String fieldValue;

                if (colonIdx >= 0) {
                    fieldName = line.substring(0, colonIdx);
                    // 冒号后的空格是可选的
                    fieldValue = (colonIdx + 1 < line.length() && line.charAt(colonIdx + 1) == ' ')
                            ? line.substring(colonIdx + 2)
                            : line.substring(colonIdx + 1);
                } else {
                    fieldName = line;
                    fieldValue = "";
                }

                hasFields = true;

                switch (fieldName) {
                    case "data" -> {
                        if (dataBuilder == null) {
                            dataBuilder = new StringBuilder();
                        } else {
                            dataBuilder.append('\n');
                        }
                        dataBuilder.append(fieldValue);
                    }
                    case "event" -> eventType = fieldValue;
                    case "id" -> eventId = fieldValue;
                    // retry 和其他未知字段忽略
                }
            }

            // 流结束，如果有未发送的事件数据则返回
            if (hasFields && dataBuilder != null) {
                return new SseEvent(eventType, dataBuilder.toString(), eventId);
            }

            return null; // 流结束
        }

        private void closeReader() {
            try {
                reader.close();
            } catch (IOException e) {
                log.debug("关闭 SSE reader 异常", e);
            }
        }
    }
}

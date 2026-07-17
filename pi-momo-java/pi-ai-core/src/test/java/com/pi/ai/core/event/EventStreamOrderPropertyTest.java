package com.pi.ai.core.event;

import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 属性测试：EventStream 事件顺序保持。
 *
 * <p>验证任意事件序列经过 push → Iterator 消费后，顺序与原始序列一致。
 * 同时验证并发生产者/消费者模式下的顺序保持。
 *
 * <p><b>Validates: Requirements 5.2, 5.4, 5.7</b>
 */
class EventStreamOrderPropertyTest {

    // ==================== Arbitrary 生成器 ====================

    /**
     * 生成随机字符串事件列表，避免包含终止标记 "DONE"。
     */
    @Provide
    Arbitrary<List<String>> eventLists() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .alpha()
                .numeric()
                .filter(s -> !"DONE".equals(s))
                .list()
                .ofMinSize(0)
                .ofMaxSize(200);
    }

    // ==================== 属性测试 ====================

    /**
     * 属性：同步 push 后 Iterator 消费顺序与原始序列一致。
     *
     * <p>对任意事件列表，依次 push 到 EventStream 后调用 end()，
     * 通过 Iterator 收集的结果应与原始列表完全相同（顺序和元素）。
     *
     * <p><b>Validates: Requirements 5.2, 5.4, 5.7</b>
     */
    @Property(tries = 500)
    void pushThenIterate_preservesOrder(@ForAll("eventLists") List<String> events) {
        // 创建不会被事件内容触发 isComplete 的 EventStream
        var stream = new EventStream<String, String>(
                "DONE"::equals,
                event -> event
        );

        // 推送所有事件
        for (String event : events) {
            stream.push(event);
        }
        stream.end(null);

        // 通过 Iterator 收集
        List<String> collected = collectAll(stream);

        // 验证顺序和内容完全一致
        assertThat(collected).isEqualTo(events);
    }

    /**
     * 属性：并发生产者/消费者模式下事件顺序保持。
     *
     * <p>生产者在独立线程中 push 事件，消费者在另一个线程中通过 Iterator 消费，
     * 收集到的事件顺序应与原始序列一致。
     *
     * <p><b>Validates: Requirements 5.2, 5.4, 5.7</b>
     */
    @Property(tries = 200)
    void concurrentProducerConsumer_preservesOrder(@ForAll("eventLists") List<String> events) throws Exception {
        @SuppressWarnings("resource")
        var stream = new EventStream<String, String>(
                "DONE"::equals,
                event -> event
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch producerStarted = new CountDownLatch(1);

        try {
            // 消费者线程：阻塞式迭代收集事件
            CompletableFuture<List<String>> consumerFuture = CompletableFuture.supplyAsync(() -> {
                producerStarted.countDown();
                List<String> collected = new ArrayList<>();
                for (String event : stream) {
                    collected.add(event);
                }
                return collected;
            }, executor);

            // 等待消费者线程启动
            producerStarted.await(2, TimeUnit.SECONDS);

            // 生产者线程：推送所有事件后结束流
            CompletableFuture<Void> producerFuture = CompletableFuture.runAsync(() -> {
                for (String event : events) {
                    stream.push(event);
                }
                stream.end(null);
            }, executor);

            // 等待生产者完成
            producerFuture.get(5, TimeUnit.SECONDS);

            // 等待消费者完成并获取结果
            List<String> collected = consumerFuture.get(5, TimeUnit.SECONDS);

            // 验证顺序和内容完全一致
            assertThat(collected).isEqualTo(events);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 从 EventStream 收集所有事件到列表。
     */
    private <E> List<E> collectAll(EventStream<E, ?> stream) {
        List<E> result = new ArrayList<>();
        for (E event : stream) {
            result.add(event);
        }
        return result;
    }
}

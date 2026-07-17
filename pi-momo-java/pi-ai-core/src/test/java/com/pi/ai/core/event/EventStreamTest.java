package com.pi.ai.core.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单元测试：EventStream 泛型事件流基础设施。
 * 覆盖 push、end、iterator、result、close 以及线程安全行为。
 */
class EventStreamTest {

    // ==================== 基本构造 ====================

    @Test
    void constructor_createsStreamWithIncompleteFuture() {
        var stream = createStringStream();
        assertThat(stream.result()).isNotNull();
        assertThat(stream.result().isDone()).isFalse();
    }

    // ==================== push 行为 ====================

    @Test
    void push_eventsAreDeliveredViaIterator() {
        var stream = createStringStream();
        stream.push("hello");
        stream.push("world");
        stream.end(null);

        var events = collectAll(stream);
        assertThat(events).containsExactly("hello", "world");
    }

    @Test
    void push_afterDone_isNoOp() {
        // 使用 "DONE" 作为终止事件
        var stream = createStringStream();
        stream.push("first");
        stream.push("DONE");  // 触发 isComplete
        stream.push("ignored"); // done=true 后应被忽略
        stream.end(null);

        var events = collectAll(stream);
        // "DONE" 事件本身也会被放入队列，但 "ignored" 不会
        assertThat(events).containsExactly("first", "DONE");
    }

    @Test
    void push_completeEvent_setsResultFuture() {
        var stream = createStringStream();
        stream.push("DONE");
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isEqualTo("DONE");
    }

    // ==================== end 行为 ====================

    @Test
    void end_withResult_completesResultFuture() {
        var stream = createStringStream();
        stream.push("event1");
        stream.end("final-result");

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isEqualTo("final-result");
    }

    @Test
    void end_withoutResult_doesNotCompleteResultFuture() {
        var stream = createStringStream();
        stream.push("event1");
        stream.end(null);

        // result future 未被 complete（没有 isComplete 事件，end(null) 不设置结果）
        assertThat(stream.result().isDone()).isFalse();
    }

    @Test
    void end_noArgs_doesNotCompleteResultFuture() {
        var stream = createStringStream();
        stream.push("event1");
        stream.end();

        assertThat(stream.result().isDone()).isFalse();
    }

    @Test
    void end_isIdempotent() {
        var stream = createStringStream();
        stream.end("result1");
        stream.end("result2"); // 第二次调用不应改变结果

        assertThat(stream.result().join()).isEqualTo("result1");
    }

    @Test
    void end_terminatesIterator() {
        var stream = createStringStream();
        stream.push("a");
        stream.push("b");
        stream.end("done");

        var events = collectAll(stream);
        assertThat(events).containsExactly("a", "b");
    }

    // ==================== iterator 行为 ====================

    @Test
    void iterator_emptyStream_returnsNoEvents() {
        var stream = createStringStream();
        stream.end(null);

        var events = collectAll(stream);
        assertThat(events).isEmpty();
    }

    @Test
    void iterator_preservesEventOrder() {
        var stream = createStringStream();
        for (int i = 0; i < 10; i++) {
            stream.push("event-" + i);
        }
        stream.end(null);

        var events = collectAll(stream);
        assertThat(events).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(events.get(i)).isEqualTo("event-" + i);
        }
    }

    @Test
    void iterator_next_throwsWhenNoMoreEvents() {
        var stream = createStringStream();
        stream.end(null);

        Iterator<String> it = stream.iterator();
        assertThat(it.hasNext()).isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void iterator_eventsQueuedBeforeConsumption_areDelivered() {
        var stream = createStringStream();
        // 先推送所有事件，再开始消费
        stream.push("pre1");
        stream.push("pre2");
        stream.push("pre3");
        stream.end(null);

        var events = collectAll(stream);
        assertThat(events).containsExactly("pre1", "pre2", "pre3");
    }

    // ==================== result 行为 ====================

    @SuppressWarnings("resource")
    @Test
    void result_completedByIsCompleteEvent() {
        var stream = new EventStream<Integer, Integer>(
                i -> i < 0,       // 负数为终止事件
                i -> Math.abs(i)  // 提取绝对值作为结果
        );
        stream.push(1);
        stream.push(2);
        stream.push(-5); // 终止事件
        stream.end(null);

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join()).isEqualTo(5);
    }

    @Test
    void result_firstCompleteEventWins() {
        var stream = createStringStream();
        stream.push("DONE");  // 第一个终止事件
        // done=true 后 push 被忽略，所以不会有第二个终止事件
        stream.end("other-result"); // end 的 result 也不会覆盖

        assertThat(stream.result().join()).isEqualTo("DONE");
    }

    // ==================== close 行为 ====================

    @Test
    void close_endsStreamIfNotDone() {
        var stream = createStringStream();
        stream.push("event");
        stream.close();

        // close 调用了 end()，迭代器应能正常结束
        var events = collectAll(stream);
        assertThat(events).containsExactly("event");
    }

    @Test
    void close_isNoOpIfAlreadyDone() {
        var stream = createStringStream();
        stream.end("result");
        stream.close(); // 不应抛异常

        assertThat(stream.result().join()).isEqualTo("result");
    }

    @Test
    void tryWithResources_closesStream() {
        EventStream<String, String> stream = createStringStream();
        try (stream) {
            stream.push("inside");
        }
        // close 被自动调用，迭代器应能正常结束
        var events = collectAll(stream);
        assertThat(events).containsExactly("inside");
    }

    // ==================== Iterable 支持 ====================

    @Test
    void forEachLoop_works() {
        var stream = createStringStream();
        stream.push("x");
        stream.push("y");
        stream.end(null);

        List<String> collected = new ArrayList<>();
        for (String event : stream) {
            collected.add(event);
        }
        assertThat(collected).containsExactly("x", "y");
    }

    // ==================== 线程安全 ====================

    @SuppressWarnings("resource")
    @Test
    void crossThread_producerConsumer() throws Exception {
        var stream = createStringStream();
        int eventCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch producerDone = new CountDownLatch(1);

        // 生产者线程
        executor.submit(() -> {
            for (int i = 0; i < eventCount; i++) {
                stream.push("event-" + i);
            }
            stream.end("completed");
            producerDone.countDown();
        });

        // 消费者线程
        CompletableFuture<List<String>> consumerFuture = CompletableFuture.supplyAsync(() -> {
            List<String> events = new ArrayList<>();
            for (String event : stream) {
                events.add(event);
            }
            return events;
        }, executor);

        List<String> events = consumerFuture.get(5, TimeUnit.SECONDS);
        assertThat(events).hasSize(eventCount);
        // 验证顺序保持
        for (int i = 0; i < eventCount; i++) {
            assertThat(events.get(i)).isEqualTo("event-" + i);
        }
        assertThat(stream.result().join()).isEqualTo("completed");

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void iterator_blocksUntilEventAvailable() throws Exception {
        var stream = createStringStream();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch eventReceived = new CountDownLatch(1);

        // 消费者在另一个线程中阻塞等待
        CompletableFuture<String> firstEvent = CompletableFuture.supplyAsync(() -> {
            Iterator<String> it = stream.iterator();
            if (it.hasNext()) {
                String event = it.next();
                eventReceived.countDown();
                return event;
            }
            return null;
        }, executor);

        // 稍等一下确保消费者已经在阻塞
        Thread.sleep(50);
        assertThat(firstEvent.isDone()).isFalse();

        // 推送事件，解除阻塞
        stream.push("delayed-event");
        stream.end(null);

        assertThat(eventReceived.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(firstEvent.get(5, TimeUnit.SECONDS)).isEqualTo("delayed-event");

        executor.shutdown();
    }

    // ==================== 自定义类型参数 ====================

    @SuppressWarnings("resource")
    @Test
    void customTypes_eventAndResult() {
        record MyEvent(String type, String data) {}
        record MyResult(String summary) {}

        var stream = new EventStream<MyEvent, MyResult>(
                e -> "end".equals(e.type()),
                e -> new MyResult("Summary: " + e.data())
        );

        stream.push(new MyEvent("data", "hello"));
        stream.push(new MyEvent("data", "world"));
        stream.push(new MyEvent("end", "final"));
        stream.end(null);

        var events = new ArrayList<MyEvent>();
        for (MyEvent e : stream) {
            events.add(e);
        }
        assertThat(events).hasSize(3);
        assertThat(events.get(0).data()).isEqualTo("hello");
        assertThat(events.get(2).type()).isEqualTo("end");

        assertThat(stream.result().isDone()).isTrue();
        assertThat(stream.result().join().summary()).isEqualTo("Summary: final");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个简单的 String 类型 EventStream。
     * isComplete: 事件等于 "DONE" 时为终止事件
     * extractResult: 直接返回事件本身作为结果
     */
    private EventStream<String, String> createStringStream() {
        return new EventStream<>(
                "DONE"::equals,
                event -> event
        );
    }

    /**
     * 从 EventStream 收集所有事件到列表。
     */
    private <E> List<E> collectAll(EventStream<E, ?> stream) {
        List<E> events = new ArrayList<>();
        for (E event : stream) {
            events.add(event);
        }
        return events;
    }
}

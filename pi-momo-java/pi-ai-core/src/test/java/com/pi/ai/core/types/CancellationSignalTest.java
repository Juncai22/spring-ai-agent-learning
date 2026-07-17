package com.pi.ai.core.types;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单元测试：CancellationSignal 取消机制和 StreamOptions signal 集成。
 */
class CancellationSignalTest {

    // ==================== 基本行为 ====================

    @Test
    void initialState_isNotCancelled() {
        var signal = new CancellationSignal();
        assertThat(signal.isCancelled()).isFalse();
    }

    @Test
    void afterCancel_isCancelled() {
        var signal = new CancellationSignal();
        signal.cancel();
        assertThat(signal.isCancelled()).isTrue();
    }

    @Test
    void multipleCancel_isIdempotent() {
        var signal = new CancellationSignal();
        signal.cancel();
        signal.cancel();
        signal.cancel();
        assertThat(signal.isCancelled()).isTrue();
    }

    // ==================== 线程安全 ====================

    @Test
    void concurrentCancelAndCheck_threadSafe() throws InterruptedException {
        var signal = new CancellationSignal();
        int threadCount = 16;
        var latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        // 一半线程调用 cancel，一半线程调用 isCancelled
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    latch.await(); // 所有线程同时开始
                    if (idx % 2 == 0) {
                        signal.cancel();
                    } else {
                        // 读取状态，不应抛异常
                        signal.isCancelled();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        latch.countDown(); // 释放所有线程
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // cancel 被调用后，最终状态必须为 true
        assertThat(signal.isCancelled()).isTrue();
    }

    // ==================== StreamOptions 集成 ====================

    @Test
    void streamOptions_builder_canSetSignal() {
        var signal = new CancellationSignal();
        var opts = StreamOptions.builder()
                .signal(signal)
                .build();

        assertThat(opts.getSignal()).isSameAs(signal);
    }

    @Test
    void streamOptions_builder_signalDefaultsToNull() {
        var opts = StreamOptions.builder().build();
        assertThat(opts.getSignal()).isNull();
    }

    @Test
    void streamOptions_signal_notSerialized() throws Exception {
        var signal = new CancellationSignal();
        var opts = StreamOptions.builder()
                .signal(signal)
                .temperature(0.5)
                .build();

        String json = com.pi.ai.core.util.PiAiJson.MAPPER.writeValueAsString(opts);
        assertThat(json).doesNotContain("signal");
        assertThat(json).contains("\"temperature\"");
    }

    @Test
    void streamOptions_signal_notInEquals() {
        var signal1 = new CancellationSignal();
        var signal2 = new CancellationSignal();

        var opts1 = StreamOptions.builder().temperature(0.5).signal(signal1).build();
        var opts2 = StreamOptions.builder().temperature(0.5).signal(signal2).build();

        // signal 不参与 equals 比较，两个对象应相等
        assertThat(opts1).isEqualTo(opts2);
        assertThat(opts1.hashCode()).isEqualTo(opts2.hashCode());
    }

    @Test
    void simpleStreamOptions_builder_canSetSignal() {
        var signal = new CancellationSignal();
        var opts = SimpleStreamOptions.simpleBuilder()
                .signal(signal)
                .reasoning(ThinkingLevel.HIGH)
                .build();

        assertThat(opts.getSignal()).isSameAs(signal);
        assertThat(opts.getReasoning()).isEqualTo(ThinkingLevel.HIGH);
    }
}

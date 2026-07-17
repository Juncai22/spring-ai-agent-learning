package com.pi.coding.resource;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link Debouncer}.
 * 
 * Feature: skills-hot-reload
 */
class DebouncerPropertyTest {

    /**
     * Property 5: 防抖合并
     * For any N events (N > 1) submitted within debounceDelayMs, 
     * Debouncer should only trigger one callback execution.
     * 
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    @Label("Feature: skills-hot-reload, Property 5: 防抖合并")
    void debounceConsolidatesMultipleEvents(
        @ForAll @IntRange(min = 2, max = 20) int eventCount
    ) throws InterruptedException {
        // Given
        long debounceDelay = 100; // 100ms for faster tests
        Debouncer debouncer = new Debouncer(debounceDelay);
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // When - submit multiple events rapidly
            for (int i = 0; i < eventCount; i++) {
                debouncer.submit(() -> {
                    callCount.incrementAndGet();
                    latch.countDown();
                });
            }
            
            // Wait for debounce to complete
            boolean completed = latch.await(debounceDelay + 200, TimeUnit.MILLISECONDS);
            
            // Then - only one execution should have occurred
            assertThat(completed).isTrue();
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            debouncer.shutdown();
        }
    }

    /**
     * Property 6: 防抖延迟
     * For any event submitted to Debouncer, the callback execution time 
     * should be at least debounceDelayMs after the event submission.
     * 
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    @Label("Feature: skills-hot-reload, Property 6: 防抖延迟")
    void debounceDelaysExecution(
        @ForAll @IntRange(min = 50, max = 200) int delayMs
    ) throws InterruptedException {
        // Given
        Debouncer debouncer = new Debouncer(delayMs);
        AtomicLong executionTime = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // When
            long submitTime = System.currentTimeMillis();
            debouncer.submit(() -> {
                executionTime.set(System.currentTimeMillis());
                latch.countDown();
            });
            
            // Wait for execution
            boolean completed = latch.await(delayMs + 200, TimeUnit.MILLISECONDS);
            
            // Then - execution should be at least delayMs after submission
            assertThat(completed).isTrue();
            long actualDelay = executionTime.get() - submitTime;
            // Allow some tolerance for scheduling overhead
            assertThat(actualDelay).isGreaterThanOrEqualTo(delayMs - 10);
        } finally {
            debouncer.shutdown();
        }
    }

    /**
     * Property 7: 防抖定时器重置
     * For any new event submitted during the debounce wait period,
     * Debouncer should reset the timer, making callback execution time
     * calculated from the last event submission.
     * 
     * Validates: Requirements 3.3
     */
    @Property(tries = 50)
    @Label("Feature: skills-hot-reload, Property 7: 防抖定时器重置")
    void debounceResetsTimerOnNewEvent(
        @ForAll @IntRange(min = 2, max = 5) int eventCount
    ) throws InterruptedException {
        // Given
        long debounceDelay = 100;
        long intervalBetweenEvents = 30; // Less than debounce delay
        Debouncer debouncer = new Debouncer(debounceDelay);
        AtomicLong executionTime = new AtomicLong(0);
        AtomicLong lastSubmitTime = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        try {
            // When - submit events with intervals less than debounce delay
            for (int i = 0; i < eventCount; i++) {
                lastSubmitTime.set(System.currentTimeMillis());
                debouncer.submit(() -> {
                    executionTime.set(System.currentTimeMillis());
                    latch.countDown();
                });
                if (i < eventCount - 1) {
                    Thread.sleep(intervalBetweenEvents);
                }
            }
            
            // Wait for execution
            long expectedWait = debounceDelay + 200;
            boolean completed = latch.await(expectedWait, TimeUnit.MILLISECONDS);
            
            // Then - execution should be at least debounceDelay after the LAST submission
            assertThat(completed).isTrue();
            long actualDelayFromLastSubmit = executionTime.get() - lastSubmitTime.get();
            // Allow some tolerance
            assertThat(actualDelayFromLastSubmit).isGreaterThanOrEqualTo(debounceDelay - 15);
        } finally {
            debouncer.shutdown();
        }
    }

    /**
     * Property 14: Debouncer 线程安全
     * For any concurrent submit() calls, Debouncer should handle all events
     * correctly without losing events or causing race conditions.
     * 
     * Validates: Requirements 8.4
     */
    @Property(tries = 50)
    @Label("Feature: skills-hot-reload, Property 14: Debouncer 线程安全")
    void debouncerIsThreadSafe(
        @ForAll @IntRange(min = 2, max = 10) int threadCount,
        @ForAll @IntRange(min = 1, max = 5) int eventsPerThread
    ) throws InterruptedException {
        // Given
        long debounceDelay = 100;
        Debouncer debouncer = new Debouncer(debounceDelay);
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        CountDownLatch executionLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        try {
            // When - submit events from multiple threads concurrently
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        for (int i = 0; i < eventsPerThread; i++) {
                            debouncer.submit(() -> {
                                callCount.incrementAndGet();
                                executionLatch.countDown();
                            });
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            
            // Start all threads simultaneously
            startLatch.countDown();
            
            // Wait for all submissions to complete
            doneLatch.await(5, TimeUnit.SECONDS);
            
            // Wait for debounced execution
            boolean executed = executionLatch.await(debounceDelay + 300, TimeUnit.MILLISECONDS);
            
            // Then - despite concurrent submissions, only one execution should occur
            assertThat(executed).isTrue();
            // Give a small buffer for any race conditions
            Thread.sleep(50);
            assertThat(callCount.get()).isEqualTo(1);
        } finally {
            debouncer.shutdown();
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }
}

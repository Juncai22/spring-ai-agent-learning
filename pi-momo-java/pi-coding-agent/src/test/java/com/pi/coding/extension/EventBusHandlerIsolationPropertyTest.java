package com.pi.coding.extension;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Property-based tests for EventBus Handler Isolation.
 *
 * <p><b>Property 16: EventBus Handler Isolation</b>
 * <p>One handler throwing an exception does not affect other handlers.
 *
 * <p><b>Validates: Requirement 22.4</b>
 */
class EventBusHandlerIsolationPropertyTest {

    /**
     * Property: One handler throwing an exception does not affect other handlers.
     *
     * <p>When a handler throws an exception, the event bus should catch it,
     * log the error, and continue calling the remaining handlers.
     */
    @Property(tries = 100)
    void handlerExceptionDoesNotAffectOtherHandlers(
            @ForAll @IntRange(min = 2, max = 10) int handlerCount,
            @ForAll @IntRange(min = 0, max = 9) int throwingIndex) {

        // Ensure throwingIndex is within bounds
        int actualThrowingIndex = throwingIndex % handlerCount;

        // Given: An event bus with multiple handlers, one of which throws
        EventBusController eventBus = EventBusController.create();
        AtomicInteger callCount = new AtomicInteger(0);
        List<Integer> calledIndices = new CopyOnWriteArrayList<>();

        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            eventBus.on("test_event", (String data) -> {
                calledIndices.add(index);
                callCount.incrementAndGet();
                if (index == actualThrowingIndex) {
                    throw new RuntimeException("Test exception from handler " + index);
                }
            });
        }

        // When: An event is emitted
        eventBus.emit("test_event", "test data");

        // Then: All handlers are called (including the one that throws)
        Assertions.assertThat(callCount.get())
            .as("All handlers should be called")
            .isEqualTo(handlerCount);

        // And: Handlers are called in order
        Assertions.assertThat(calledIndices)
            .hasSize(handlerCount)
            .isSorted();

        eventBus.clear();
    }

    /**
     * Property: Multiple exceptions do not prevent other handlers from running.
     *
     * <p>Even when multiple handlers throw exceptions, all handlers should still be called.
     */
    @Property(tries = 50)
    void multipleExceptionsDoNotPreventOtherHandlers(
            @ForAll @IntRange(min = 3, max = 8) int handlerCount,
            @ForAll @IntRange(min = 1, max = 4) int throwingCount) {

        // Ensure throwingCount is within bounds
        int actualThrowingCount = Math.min(throwingCount, handlerCount - 1);

        // Given: An event bus with multiple handlers, several of which throw
        EventBusController eventBus = EventBusController.create();
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            eventBus.on("test_event", (String data) -> {
                callCount.incrementAndGet();
                if (index < actualThrowingCount) {
                    exceptionCount.incrementAndGet();
                    throw new RuntimeException("Test exception from handler " + index);
                }
            });
        }

        // When: An event is emitted
        eventBus.emit("test_event", "test data");

        // Then: All handlers are called
        Assertions.assertThat(callCount.get())
            .as("All handlers should be called")
            .isEqualTo(handlerCount);

        // And: The expected number of exceptions were thrown
        Assertions.assertThat(exceptionCount.get())
            .as("Expected number of exceptions")
            .isEqualTo(actualThrowingCount);

        eventBus.clear();
    }

    /**
     * Property: Handlers are called in registration order.
     */
    @Property(tries = 100)
    void handlersCalledInRegistrationOrder(
            @ForAll @IntRange(min = 1, max = 10) int handlerCount) {

        // Given: An event bus with multiple handlers
        EventBusController eventBus = EventBusController.create();
        List<Integer> callOrder = new CopyOnWriteArrayList<>();

        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            eventBus.on("test_event", (String data) -> {
                callOrder.add(index);
            });
        }

        // When: An event is emitted
        eventBus.emit("test_event", "test data");

        // Then: Handlers are called in registration order
        Assertions.assertThat(callOrder)
            .hasSize(handlerCount)
            .isSorted();

        for (int i = 0; i < handlerCount; i++) {
            Assertions.assertThat(callOrder.get(i)).isEqualTo(i);
        }

        eventBus.clear();
    }

    /**
     * Property: Unsubscribe removes only the specific handler.
     */
    @Property(tries = 50)
    void unsubscribeRemovesOnlySpecificHandler(
            @ForAll @IntRange(min = 2, max = 8) int handlerCount,
            @ForAll @IntRange(min = 0, max = 7) int unsubscribeIndex) {

        // Ensure unsubscribeIndex is within bounds
        int actualUnsubscribeIndex = unsubscribeIndex % handlerCount;

        // Given: An event bus with multiple handlers
        EventBusController eventBus = EventBusController.create();
        List<Integer> calledIndices = new CopyOnWriteArrayList<>();
        List<Runnable> unsubscribers = new ArrayList<>();

        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            Runnable unsubscribe = eventBus.on("test_event", (String data) -> {
                calledIndices.add(index);
            });
            unsubscribers.add(unsubscribe);
        }

        // When: One handler is unsubscribed and event is emitted
        unsubscribers.get(actualUnsubscribeIndex).run();
        eventBus.emit("test_event", "test data");

        // Then: All handlers except the unsubscribed one are called
        Assertions.assertThat(calledIndices)
            .hasSize(handlerCount - 1)
            .doesNotContain(actualUnsubscribeIndex);

        eventBus.clear();
    }

    /**
     * Property: Clear removes all handlers.
     */
    @Property(tries = 50)
    void clearRemovesAllHandlers(
            @ForAll @IntRange(min = 1, max = 10) int handlerCount) {

        // Given: An event bus with multiple handlers
        EventBusController eventBus = EventBusController.create();
        AtomicInteger callCount = new AtomicInteger(0);

        for (int i = 0; i < handlerCount; i++) {
            eventBus.on("test_event", (String data) -> {
                callCount.incrementAndGet();
            });
        }

        // When: Clear is called and event is emitted
        eventBus.clear();
        eventBus.emit("test_event", "test data");

        // Then: No handlers are called
        Assertions.assertThat(callCount.get()).isZero();
    }

    /**
     * Property: Different event names have independent handlers.
     */
    @Property(tries = 50)
    void differentEventNamesHaveIndependentHandlers(
            @ForAll @IntRange(min = 1, max = 5) int event1Handlers,
            @ForAll @IntRange(min = 1, max = 5) int event2Handlers) {

        // Given: An event bus with handlers for different events
        EventBusController eventBus = EventBusController.create();
        AtomicInteger event1Count = new AtomicInteger(0);
        AtomicInteger event2Count = new AtomicInteger(0);

        for (int i = 0; i < event1Handlers; i++) {
            eventBus.on("event1", (String data) -> event1Count.incrementAndGet());
        }

        for (int i = 0; i < event2Handlers; i++) {
            eventBus.on("event2", (String data) -> event2Count.incrementAndGet());
        }

        // When: Only event1 is emitted
        eventBus.emit("event1", "test data");

        // Then: Only event1 handlers are called
        Assertions.assertThat(event1Count.get()).isEqualTo(event1Handlers);
        Assertions.assertThat(event2Count.get()).isZero();

        // When: event2 is emitted
        eventBus.emit("event2", "test data");

        // Then: event2 handlers are also called
        Assertions.assertThat(event2Count.get()).isEqualTo(event2Handlers);

        eventBus.clear();
    }

    /**
     * Property: Emitting to non-existent event does not throw.
     */
    @Property(tries = 20)
    void emittingToNonExistentEventDoesNotThrow(
            @ForAll String eventName,
            @ForAll String data) {

        // Given: An empty event bus
        EventBusController eventBus = EventBusController.create();

        // When/Then: Emitting to non-existent event does not throw
        Assertions.assertThatCode(() -> eventBus.emit(eventName, data))
            .doesNotThrowAnyException();

        eventBus.clear();
    }
}

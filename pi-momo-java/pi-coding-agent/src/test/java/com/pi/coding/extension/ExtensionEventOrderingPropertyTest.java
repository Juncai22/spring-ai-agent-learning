package com.pi.coding.extension;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.assertj.core.api.Assertions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Property-based tests for Extension Event Ordering.
 *
 * <p><b>Property 6: Extension Event Ordering</b>
 * <p>ExtensionRunner emits events to handlers in registration order,
 * and all handlers are called even if some throw exceptions.
 *
 * <p><b>Validates: Requirement 5.16</b>
 */
class ExtensionEventOrderingPropertyTest {

    /**
     * Property: Events are emitted to handlers in registration order.
     *
     * <p>When multiple handlers are registered for the same event type,
     * they should be called in the order they were registered.
     */
    @Property(tries = 100)
    void eventHandlersAreCalledInRegistrationOrder(
            @ForAll @IntRange(min = 1, max = 10) int handlerCount) {

        // Given: An extension runner with multiple handlers registered in order
        ExtensionRunner runner = new ExtensionRunner();
        List<Integer> callOrder = new CopyOnWriteArrayList<>();

        List<ExtensionFactory> factories = new ArrayList<>();
        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            factories.add(api -> {
                api.on("session_start", (event, ctx) -> {
                    callOrder.add(index);
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        runner.loadExtensions(factories);

        // When: An event is emitted
        runner.emit(new ExtensionEvent.SessionStartEvent()).join();

        // Then: Handlers are called in registration order
        Assertions.assertThat(callOrder)
            .hasSize(handlerCount)
            .isSorted();

        for (int i = 0; i < handlerCount; i++) {
            Assertions.assertThat(callOrder.get(i)).isEqualTo(i);
        }

        runner.dispose();
    }

    /**
     * Property: All handlers are called even if some throw exceptions.
     *
     * <p>When a handler throws an exception, the runner should log the error
     * and continue calling the remaining handlers.
     */
    @Property(tries = 50)
    void allHandlersCalledEvenWhenSomeThrow(
            @ForAll @IntRange(min = 2, max = 8) int handlerCount,
            @ForAll @IntRange(min = 0, max = 7) int throwingIndex) {

        // Ensure throwingIndex is within bounds
        int actualThrowingIndex = throwingIndex % handlerCount;

        // Given: An extension runner with handlers, one of which throws
        ExtensionRunner runner = new ExtensionRunner();
        AtomicInteger callCount = new AtomicInteger(0);
        List<Integer> calledIndices = new CopyOnWriteArrayList<>();

        List<ExtensionFactory> factories = new ArrayList<>();
        for (int i = 0; i < handlerCount; i++) {
            final int index = i;
            factories.add(api -> {
                api.on("session_start", (event, ctx) -> {
                    calledIndices.add(index);
                    callCount.incrementAndGet();
                    if (index == actualThrowingIndex) {
                        throw new RuntimeException("Test exception from handler " + index);
                    }
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        runner.loadExtensions(factories);

        // When: An event is emitted
        runner.emit(new ExtensionEvent.SessionStartEvent()).join();

        // Then: All handlers are called (including the one that throws)
        Assertions.assertThat(callCount.get())
            .as("All handlers should be called")
            .isEqualTo(handlerCount);

        // And: Handlers are still called in order
        Assertions.assertThat(calledIndices)
            .hasSize(handlerCount)
            .isSorted();

        runner.dispose();
    }

    /**
     * Property: Multiple events maintain independent handler ordering.
     *
     * <p>When handlers are registered for different event types,
     * each event type maintains its own handler order.
     */
    @Property(tries = 50)
    void multipleEventTypesMaintainIndependentOrdering(
            @ForAll @IntRange(min = 1, max = 5) int sessionStartHandlers,
            @ForAll @IntRange(min = 1, max = 5) int agentStartHandlers) {

        // Given: An extension runner with handlers for different event types
        ExtensionRunner runner = new ExtensionRunner();
        List<String> sessionStartOrder = new CopyOnWriteArrayList<>();
        List<String> agentStartOrder = new CopyOnWriteArrayList<>();

        List<ExtensionFactory> factories = new ArrayList<>();

        // Register session_start handlers
        for (int i = 0; i < sessionStartHandlers; i++) {
            final int index = i;
            factories.add(api -> {
                api.on("session_start", (event, ctx) -> {
                    sessionStartOrder.add("session_" + index);
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        // Register agent_start handlers
        for (int i = 0; i < agentStartHandlers; i++) {
            final int index = i;
            factories.add(api -> {
                api.on("agent_start", (event, ctx) -> {
                    agentStartOrder.add("agent_" + index);
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        runner.loadExtensions(factories);

        // When: Both event types are emitted
        runner.emit(new ExtensionEvent.SessionStartEvent()).join();
        runner.emit(new ExtensionEvent.AgentStartEvent()).join();

        // Then: Each event type has its handlers called in order
        Assertions.assertThat(sessionStartOrder)
            .hasSize(sessionStartHandlers);
        for (int i = 0; i < sessionStartHandlers; i++) {
            Assertions.assertThat(sessionStartOrder.get(i)).isEqualTo("session_" + i);
        }

        Assertions.assertThat(agentStartOrder)
            .hasSize(agentStartHandlers);
        for (int i = 0; i < agentStartHandlers; i++) {
            Assertions.assertThat(agentStartOrder.get(i)).isEqualTo("agent_" + i);
        }

        runner.dispose();
    }

    /**
     * Property: hasHandlers returns correct result.
     *
     * <p>hasHandlers should return true only for event types that have
     * at least one registered handler.
     */
    @Property(tries = 50)
    void hasHandlersReturnsCorrectResult(
            @ForAll @IntRange(min = 0, max = 5) int sessionStartHandlers,
            @ForAll @IntRange(min = 0, max = 5) int agentStartHandlers) {

        // Given: An extension runner with varying handler counts
        ExtensionRunner runner = new ExtensionRunner();

        List<ExtensionFactory> factories = new ArrayList<>();

        for (int i = 0; i < sessionStartHandlers; i++) {
            factories.add(api -> {
                api.on("session_start", (event, ctx) -> CompletableFuture.completedFuture(null));
            });
        }

        for (int i = 0; i < agentStartHandlers; i++) {
            factories.add(api -> {
                api.on("agent_start", (event, ctx) -> CompletableFuture.completedFuture(null));
            });
        }

        runner.loadExtensions(factories);

        // Then: hasHandlers returns correct result
        Assertions.assertThat(runner.hasHandlers("session_start"))
            .isEqualTo(sessionStartHandlers > 0);
        Assertions.assertThat(runner.hasHandlers("agent_start"))
            .isEqualTo(agentStartHandlers > 0);
        Assertions.assertThat(runner.hasHandlers("nonexistent_event"))
            .isFalse();

        runner.dispose();
    }

    /**
     * Property: Disposed runner does not emit events.
     *
     * <p>After dispose() is called, emit() should not call any handlers.
     */
    @Property(tries = 20)
    void disposedRunnerDoesNotEmitEvents(
            @ForAll @IntRange(min = 1, max = 5) int handlerCount) {

        // Given: An extension runner with handlers
        ExtensionRunner runner = new ExtensionRunner();
        AtomicInteger callCount = new AtomicInteger(0);

        List<ExtensionFactory> factories = new ArrayList<>();
        for (int i = 0; i < handlerCount; i++) {
            factories.add(api -> {
                api.on("session_start", (event, ctx) -> {
                    callCount.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
            });
        }

        runner.loadExtensions(factories);

        // When: Runner is disposed and event is emitted
        runner.dispose();
        runner.emit(new ExtensionEvent.SessionStartEvent()).join();

        // Then: No handlers are called
        Assertions.assertThat(callCount.get()).isZero();
    }
}

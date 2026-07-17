package com.pi.ai.core.types;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消信号，用于中断正在进行的流式请求。
 * 提供了一种线程安全的方式来通知正在进行的请求应该被取消。
 *
 * <p>基于 {@link AtomicBoolean} 实现，线程安全。
 * 对应 TypeScript 中的 {@code AbortSignal} 概念。
 *
 * <p>用法示例：
 * <pre>{@code
 * CancellationSignal signal = new CancellationSignal();
 * var opts = StreamOptions.builder().signal(signal).build();
 * // ... 在另一个线程中取消
 * signal.cancel();
 * }</pre>
 *
 * <p>取消操作是幂等的，多次调用 {@link #cancel()} 不会产生副作用。
 * 在流式请求处理循环中，应定期检查 {@link #isCancelled()} 以优雅地终止处理。
 */
// Step 1: 使用 final class 确保不可继承
// 原因：取消信号是一个轻量级工具类，不需要继承扩展
public final class CancellationSignal {

    /** 内部取消状态，保证线程安全。 */
    // Step 2: 使用 AtomicBoolean 而非 volatile boolean
    // 原因：AtomicBoolean 提供原子的 get-and-set 操作，确保线程安全
    // 初始值为 false，表示未取消状态
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * 设置取消状态。多次调用是幂等的。
     */
    // Step 3: 取消操作，将状态标记为 true
    // 幂等性：多次调用 cancel() 等同于调用一次，不会产生额外副作用
    // 线程安全：AtomicBoolean.set(true) 是原子的
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 检查是否已取消。
     *
     * @return 如果 {@link #cancel()} 已被调用则返回 {@code true}
     */
    // Step 4: 检查取消状态
    // 在流式处理循环中应定期调用此方法，发现取消后优雅终止
    // 线程安全：AtomicBoolean.get() 是原子的
    public boolean isCancelled() {
        return cancelled.get();
    }
}
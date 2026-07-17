package com.pi.coding.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用防抖器。
 * 在指定时间窗口内合并多次触发，只执行最后一次。
 *
 * <p>防抖器用于处理频繁触发的事件，例如文件系统变化事件。
 * 当事件发生时，防抖器会等待指定的延迟时间，如果在此期间
 * 有新的事件发生，则重置定时器。只有当延迟时间内没有新事件
 * 发生时，才会执行回调。
 *
 * <p>示例用法：
 * <pre>{@code
 * Debouncer debouncer = new Debouncer(500); // 500ms 防抖延迟
 * 
 * // 多次快速调用只会触发一次执行
 * debouncer.submit(() -> System.out.println("Executed!"));
 * debouncer.submit(() -> System.out.println("Executed!"));
 * debouncer.submit(() -> System.out.println("Executed!"));
 * // 500ms 后只输出一次 "Executed!"
 * 
 * // 使用完毕后关闭
 * debouncer.shutdown();
 * }</pre>
 *
 * <p>线程安全：此类是线程安全的，可以从多个线程并发调用 submit() 方法。
 */
public class Debouncer {
    
    private static final Logger logger = LoggerFactory.getLogger(Debouncer.class);
    
    private final long delayMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<ScheduledFuture<?>> pendingTask;
    private volatile boolean shutdown;
    
    /**
     * 创建一个防抖器。
     *
     * @param delayMs 防抖延迟时间（毫秒），必须大于 0
     * @throws IllegalArgumentException 如果 delayMs <= 0
     */
    public Debouncer(long delayMs) {
        if (delayMs <= 0) {
            throw new IllegalArgumentException("delayMs must be positive, got: " + delayMs);
        }
        this.delayMs = delayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "debouncer-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.pendingTask = new AtomicReference<>();
        this.shutdown = false;
    }
    
    /**
     * 提交一个任务。
     * 如果在延迟时间内有新任务提交，则取消之前的任务并重置定时器。
     *
     * @param task 要执行的任务，不能为 null
     * @throws IllegalArgumentException 如果 task 为 null
     * @throws IllegalStateException 如果防抖器已关闭
     */
    public synchronized void submit(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task cannot be null");
        }
        if (shutdown) {
            throw new IllegalStateException("Debouncer has been shutdown");
        }
        
        // 取消之前的待执行任务
        ScheduledFuture<?> previous = pendingTask.get();
        if (previous != null) {
            previous.cancel(false);
        }
        
        // 调度新任务
        ScheduledFuture<?> newTask = scheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.warn("Error executing debounced task", e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        
        // 原子更新待执行任务引用
        pendingTask.set(newTask);
    }
    
    /**
     * 取消所有待执行的任务。
     * 如果有任务正在等待执行，将被取消。
     */
    public void cancel() {
        ScheduledFuture<?> current = pendingTask.getAndSet(null);
        if (current != null) {
            current.cancel(false);
        }
    }
    
    /**
     * 关闭防抖器，释放资源。
     * 关闭后不能再提交新任务。
     *
     * <p>此方法会：
     * <ul>
     *   <li>取消所有待执行的任务</li>
     *   <li>关闭调度器线程池</li>
     * </ul>
     */
    public void shutdown() {
        shutdown = true;
        cancel();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 检查防抖器是否已关闭。
     *
     * @return 如果已关闭返回 true
     */
    public boolean isShutdown() {
        return shutdown;
    }
    
    /**
     * 获取防抖延迟时间。
     *
     * @return 延迟时间（毫秒）
     */
    public long getDelayMs() {
        return delayMs;
    }
    
    /**
     * 检查是否有待执行的任务。
     *
     * @return 如果有待执行的任务返回 true
     */
    public boolean hasPendingTask() {
        ScheduledFuture<?> current = pendingTask.get();
        return current != null && !current.isDone();
    }
}
